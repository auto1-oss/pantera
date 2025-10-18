use anyhow::{Context, Result};
use base64::{engine::general_purpose, Engine as _};
use clap::Parser;
use futures::stream::{self, StreamExt};
use indicatif::{MultiProgress, ProgressBar, ProgressStyle};
use reqwest::Client;
use sha1::Sha1;
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, UNIX_EPOCH};
use tokio::sync::{Mutex, Semaphore};
use tokio_util::io::ReaderStream;
use tracing::{debug, error, info, warn};
use walkdir::WalkDir;

#[derive(Parser, Debug, Clone)]
#[command(name = "artipie-import-cli")]
#[command(about = "Production-grade Artipie artifact importer", version)]
struct Args {
    /// Artipie server URL
    #[arg(long)]
    url: String,

    /// Export directory containing artifacts
    #[arg(long)]
    export_dir: PathBuf,

    /// Authentication token (for Bearer auth)
    #[arg(long)]
    token: Option<String>,

    /// Username for basic authentication
    #[arg(long)]
    username: Option<String>,

    /// Password for basic authentication
    #[arg(long)]
    password: Option<String>,

    /// Maximum concurrent uploads (default: CPU cores * 50)
    #[arg(long)]
    concurrency: Option<usize>,

    /// Batch size for processing
    #[arg(long, default_value = "1000")]
    batch_size: usize,

    /// Progress log file
    #[arg(long, default_value = "progress.log")]
    progress_log: PathBuf,

    /// Failures directory
    #[arg(long, default_value = "failed")]
    failures_dir: PathBuf,

    /// Resume from progress log
    #[arg(long)]
    resume: bool,

    /// Retry only failed uploads from failures directory
    #[arg(long)]
    retry: bool,

    /// Request timeout in seconds
    #[arg(long, default_value = "300")]
    timeout: u64,

    /// Max retries per file
    #[arg(long, default_value = "5")]
    max_retries: u32,

    /// HTTP connection pool size per thread
    #[arg(long, default_value = "10")]
    pool_size: usize,

    /// Enable verbose logging
    #[arg(long, short)]
    verbose: bool,

    /// Dry run - scan only, don't upload
    #[arg(long)]
    dry_run: bool,

    /// Report file path
    #[arg(long, default_value = "import_report.json")]
    report: PathBuf,

    /// Checksum policy: COMPUTE, METADATA, or SKIP
    #[arg(long, default_value = "SKIP")]
    checksum_policy: String,
}

#[derive(Debug, Clone)]
struct UploadTask {
    repo_name: String,
    repo_type: String,
    relative_path: String,
    file_path: PathBuf,
    size: u64,
    created: u64,
}

#[derive(Debug)]
enum UploadStatus {
    Success,
    Already,
    Failed(String),
    Quarantined(String),
}

impl UploadTask {
    fn idempotency_key(&self) -> String {
        format!("{}|{}", self.repo_name, self.relative_path)
    }

    async fn compute_checksums(&self) -> Result<(String, String, String)> {
        use tokio::io::AsyncReadExt;
        
        let mut file = tokio::fs::File::open(&self.file_path).await
            .with_context(|| format!("Failed to open file: {:?}", self.file_path))?;
        
        let mut md5 = md5::Context::new();
        let mut sha1 = Sha1::new();
        let mut sha256 = Sha256::new();

        let mut buffer = vec![0u8; 65536]; // 64KB buffer for better performance
        loop {
            let n = file.read(&mut buffer).await?;
            if n == 0 {
                break;
            }
            md5.consume(&buffer[..n]);
            sha1.update(&buffer[..n]);
            sha256.update(&buffer[..n]);
        }

        Ok((
            format!("{:x}", md5.compute()),
            hex::encode(sha1.finalize()),
            hex::encode(sha256.finalize()),
        ))
    }
}

struct ProgressTracker {
    completed: Arc<Mutex<HashSet<String>>>,
    file: Arc<Mutex<std::io::BufWriter<File>>>,
    success_count: Arc<AtomicUsize>,
    already_count: Arc<AtomicUsize>,
    failed_count: Arc<AtomicUsize>,
    quarantine_count: Arc<AtomicUsize>,
    bytes_uploaded: Arc<AtomicU64>,
}

impl ProgressTracker {
    fn new(log_path: PathBuf, resume: bool) -> Result<Self> {
        let mut completed = HashSet::new();

        if resume && log_path.exists() {
            info!("Loading progress log from {:?}...", log_path);
            let file = File::open(&log_path)?;
            let reader = BufReader::new(file);
            let mut count = 0;
            for line in reader.lines() {
                if let Ok(line) = line {
                    if let Some(key) = line.split('|').next() {
                        completed.insert(key.to_string());
                        count += 1;
                        if count % 100000 == 0 {
                            info!("  Loaded {} completed tasks...", count);
                        }
                    }
                }
            }
            info!("Loaded {} completed tasks", count);
        }

        let file = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log_path)
            .with_context(|| format!("Failed to open progress log: {:?}", log_path))?;

        Ok(Self {
            completed: Arc::new(Mutex::new(completed)),
            file: Arc::new(Mutex::new(std::io::BufWriter::with_capacity(1024 * 1024, file))),
            success_count: Arc::new(AtomicUsize::new(0)),
            already_count: Arc::new(AtomicUsize::new(0)),
            failed_count: Arc::new(AtomicUsize::new(0)),
            quarantine_count: Arc::new(AtomicUsize::new(0)),
            bytes_uploaded: Arc::new(AtomicU64::new(0)),
        })
    }

    async fn is_completed(&self, key: &str) -> bool {
        self.completed.lock().await.contains(key)
    }

    async fn get_completed_keys(&self) -> HashSet<String> {
        self.completed.lock().await.clone()
    }

    async fn mark_completed(&self, task: &UploadTask, status: &UploadStatus) -> Result<()> {
        let key = task.idempotency_key();
        let mut completed = self.completed.lock().await;
        
        if completed.insert(key.clone()) {
            let mut bufw = self.file.lock().await;
            writeln!(
                bufw,
                "{}|{}|{}|{}",
                key,
                task.repo_name,
                task.relative_path,
                match status {
                    UploadStatus::Success => "success",
                    UploadStatus::Already => "already",
                    UploadStatus::Failed(_) => "failed",
                    UploadStatus::Quarantined(_) => "quarantined",
                }
            )?;
            // Periodic flush to reduce syscall overhead
            let done = self.success_count.load(Ordering::Relaxed)
                + self.already_count.load(Ordering::Relaxed)
                + self.failed_count.load(Ordering::Relaxed)
                + self.quarantine_count.load(Ordering::Relaxed);
            if done % 1000 == 0 {
                bufw.flush()?;
            }
        }

        // Update counters
        match status {
            UploadStatus::Success => {
                self.success_count.fetch_add(1, Ordering::Relaxed);
                self.bytes_uploaded.fetch_add(task.size, Ordering::Relaxed);
            }
            UploadStatus::Already => {
                self.already_count.fetch_add(1, Ordering::Relaxed);
            }
            UploadStatus::Failed(_) => {
                self.failed_count.fetch_add(1, Ordering::Relaxed);
            }
            UploadStatus::Quarantined(_) => {
                self.quarantine_count.fetch_add(1, Ordering::Relaxed);
            }
        }

        Ok(())
    }

    fn get_stats(&self) -> (usize, usize, usize, usize, u64) {
        (
            self.success_count.load(Ordering::Relaxed),
            self.already_count.load(Ordering::Relaxed),
            self.failed_count.load(Ordering::Relaxed),
            self.quarantine_count.load(Ordering::Relaxed),
            self.bytes_uploaded.load(Ordering::Relaxed),
        )
    }
}

#[derive(Debug, Clone)]
struct RepoStats {
    success: usize,
    already: usize,
    failed: usize,
    quarantined: usize,
}

impl RepoStats {
    fn new() -> Self {
        Self {
            success: 0,
            already: 0,
            failed: 0,
            quarantined: 0,
        }
    }
    
    fn total(&self) -> usize {
        self.success + self.already + self.failed + self.quarantined
    }
}

struct SummaryTracker {
    stats: Arc<Mutex<std::collections::HashMap<String, RepoStats>>>,
}

impl SummaryTracker {
    fn new() -> Self {
        Self {
            stats: Arc::new(Mutex::new(std::collections::HashMap::new())),
        }
    }
    
    async fn mark_success(&self, repo: &str, already: bool) {
        let mut stats = self.stats.lock().await;
        let repo_stats = stats.entry(repo.to_string()).or_insert_with(RepoStats::new);
        if already {
            repo_stats.already += 1;
        } else {
            repo_stats.success += 1;
        }
    }
    
    async fn mark_failed(&self, repo: &str) {
        let mut stats = self.stats.lock().await;
        let repo_stats = stats.entry(repo.to_string()).or_insert_with(RepoStats::new);
        repo_stats.failed += 1;
    }
    
    async fn mark_quarantined(&self, repo: &str) {
        let mut stats = self.stats.lock().await;
        let repo_stats = stats.entry(repo.to_string()).or_insert_with(RepoStats::new);
        repo_stats.quarantined += 1;
    }
    
    async fn write_report(&self, path: &Path) -> Result<()> {
        use serde_json::json;
        
        let stats = self.stats.lock().await;
        let mut total_success = 0;
        let mut total_already = 0;
        let mut total_failed = 0;
        let mut total_quarantined = 0;
        
        let mut repos = serde_json::Map::new();
        for (repo, repo_stats) in stats.iter() {
            total_success += repo_stats.success;
            total_already += repo_stats.already;
            total_failed += repo_stats.failed;
            total_quarantined += repo_stats.quarantined;
            
            repos.insert(
                repo.clone(),
                json!({
                    "success": repo_stats.success,
                    "already": repo_stats.already,
                    "failures": repo_stats.failed,
                    "quarantined": repo_stats.quarantined,
                    "total": repo_stats.total(),
                }),
            );
        }
        
        let report = json!({
            "totalSuccess": total_success,
            "totalAlready": total_already,
            "totalFailures": total_failed + total_quarantined,
            "repos": repos,
        });
        
        tokio::fs::write(path, serde_json::to_string_pretty(&report)?).await?;
        Ok(())
    }
    
    async fn render_table(&self) -> String {
        let stats = self.stats.lock().await;
        
        // Sort repositories by name
        let mut entries: Vec<_> = stats.iter().collect();
        entries.sort_by_key(|(name, _)| *name);
        
        // Calculate totals
        let mut total_success = 0;
        let mut total_already = 0;
        let mut total_failed = 0;
        let mut total_quarantined = 0;
        
        for (_, repo_stats) in entries.iter() {
            total_success += repo_stats.success;
            total_already += repo_stats.already;
            total_failed += repo_stats.failed;
            total_quarantined += repo_stats.quarantined;
        }
        
        let total_all = total_success + total_already + total_failed + total_quarantined;
        
        // Calculate column widths
        let mut w_repo = "Repository".len();
        let mut w_success = "Success".len();
        let mut w_already = "Already".len();
        let mut w_failed = "Failed".len();
        let mut w_quarantined = "Quarantined".len();
        let mut w_total = "Total".len();
        
        for (repo, repo_stats) in entries.iter() {
            w_repo = w_repo.max(repo.len());
            w_success = w_success.max(repo_stats.success.to_string().len());
            w_already = w_already.max(repo_stats.already.to_string().len());
            w_failed = w_failed.max(repo_stats.failed.to_string().len());
            w_quarantined = w_quarantined.max(repo_stats.quarantined.to_string().len());
            w_total = w_total.max(repo_stats.total().to_string().len());
        }
        
        // Account for TOTAL row
        w_repo = w_repo.max("TOTAL".len());
        w_success = w_success.max(total_success.to_string().len());
        w_already = w_already.max(total_already.to_string().len());
        w_failed = w_failed.max(total_failed.to_string().len());
        w_quarantined = w_quarantined.max(total_quarantined.to_string().len());
        w_total = w_total.max(total_all.to_string().len());
        
        let mut table = String::new();
        
        // Header
        table.push_str(&format!(
            "| {:<w_repo$} | {:>w_success$} | {:>w_already$} | {:>w_failed$} | {:>w_quarantined$} | {:>w_total$} |\n",
            "Repository", "Success", "Already", "Failed", "Quarantined", "Total",
            w_repo = w_repo,
            w_success = w_success,
            w_already = w_already,
            w_failed = w_failed,
            w_quarantined = w_quarantined,
            w_total = w_total,
        ));
        
        // Separator
        table.push_str(&format!(
            "+{}+{}+{}+{}+{}+{}+\n",
            "-".repeat(w_repo + 2),
            "-".repeat(w_success + 2),
            "-".repeat(w_already + 2),
            "-".repeat(w_failed + 2),
            "-".repeat(w_quarantined + 2),
            "-".repeat(w_total + 2),
        ));
        
        // Rows
        for (repo, repo_stats) in entries.iter() {
            table.push_str(&format!(
                "| {:<w_repo$} | {:>w_success$} | {:>w_already$} | {:>w_failed$} | {:>w_quarantined$} | {:>w_total$} |\n",
                repo,
                repo_stats.success,
                repo_stats.already,
                repo_stats.failed,
                repo_stats.quarantined,
                repo_stats.total(),
                w_repo = w_repo,
                w_success = w_success,
                w_already = w_already,
                w_failed = w_failed,
                w_quarantined = w_quarantined,
                w_total = w_total,
            ));
        }
        
        // Separator before totals
        table.push_str(&format!(
            "+{}+{}+{}+{}+{}+{}+\n",
            "-".repeat(w_repo + 2),
            "-".repeat(w_success + 2),
            "-".repeat(w_already + 2),
            "-".repeat(w_failed + 2),
            "-".repeat(w_quarantined + 2),
            "-".repeat(w_total + 2),
        ));
        
        // Totals row
        table.push_str(&format!(
            "| {:<w_repo$} | {:>w_success$} | {:>w_already$} | {:>w_failed$} | {:>w_quarantined$} | {:>w_total$} |\n",
            "TOTAL",
            total_success,
            total_already,
            total_failed,
            total_quarantined,
            total_all,
            w_repo = w_repo,
            w_success = w_success,
            w_already = w_already,
            w_failed = w_failed,
            w_quarantined = w_quarantined,
            w_total = w_total,
        ));
        
        table
    }
}

// Attempt to load checksum values from sidecar files ("file.ext.sha1" etc.)
async fn read_sidecar_checksums(path: &Path) -> Result<(Option<String>, Option<String>, Option<String>)> {
    let mut md5: Option<String> = None;
    let mut sha1: Option<String> = None;
    let mut sha256: Option<String> = None;

    let read_first_token = |p: &Path| -> Result<Option<String>> {
        if !p.exists() {
            return Ok(None);
        }
        let f = File::open(p)?;
        let mut reader = BufReader::new(f);
        let mut line = String::new();
        reader.read_line(&mut line)?;
        if line.trim().is_empty() {
            return Ok(None);
        }
        let token = line.split_whitespace().next().unwrap_or("").trim().to_lowercase();
        Ok(if token.is_empty() { None } else { Some(token) })
    };

    if let Some(stem) = path.file_name().and_then(|n| n.to_str()) {
        let md5p = path.with_file_name(format!("{}.md5", stem));
        let sha1p = path.with_file_name(format!("{}.sha1", stem));
        let sha256p = path.with_file_name(format!("{}.sha256", stem));
        md5 = read_first_token(&md5p)?;
        sha1 = read_first_token(&sha1p)?;
        sha256 = read_first_token(&sha256p)?;
    }
    Ok((md5, sha1, sha256))
}

async fn upload_file(
    client: &Client,
    task: &UploadTask,
    args: &Args,
    progress: &ProgressTracker,
) -> Result<UploadStatus> {
    // Use /.import/ endpoint like Java CLI - this is critical!
    let url = format!("{}/.import/{}/{}", args.url, task.repo_name, task.relative_path);

    // Resolve checksum policy and values
    let policy = args.checksum_policy.trim().to_uppercase();
    let (md5, sha1, sha256): (Option<String>, Option<String>, Option<String>) = match policy.as_str() {
        // Only compute when explicitly requested
        "COMPUTE" => {
            debug!("Computing checksums for {:?}", task.file_path);
            let (m, s1, s256) = task.compute_checksums().await
                .with_context(|| format!("Failed to compute checksums for {:?}", task.file_path))?;
            (Some(m), Some(s1), Some(s256))
        }
        // Prefer sidecar files if present
        "METADATA" => {
            match read_sidecar_checksums(&task.file_path).await {
                Ok((m, s1, s256)) => (m, s1, s256),
                Err(_) => (None, None, None),
            }
        }
        // SKIP or unknown: do not send checksum headers
        _ => (None, None, None),
    };

    // Determine authorization header once (outside retry loop)
    let auth_header = if let (Some(user), Some(pass)) = (&args.username, &args.password) {
        // Basic authentication
        let credentials = format!("{}:{}", user, pass);
        let encoded = general_purpose::STANDARD.encode(credentials.as_bytes());
        format!("Basic {}", encoded)
    } else if let Some(token) = &args.token {
        // Bearer token authentication
        format!("Bearer {}", token)
    } else {
        return Err(anyhow::anyhow!(
            "Either --token or both --username and --password must be provided"
        ));
    };

    for attempt in 1..=args.max_retries {
        debug!("Attempt {}/{} for {}", attempt, args.max_retries, task.relative_path);
        
        // Open file and stream body without loading into memory
        debug!("Opening file {:?}", task.file_path);
        let file = tokio::fs::File::open(&task.file_path).await
            .with_context(|| format!("Failed to open file: {:?}", task.file_path))?;
        let stream = ReaderStream::new(file);
        let body = reqwest::Body::wrap_stream(stream);

        // Build request with headers matching Java CLI exactly
        let mut request = client
            .put(&url)
            .header("Authorization", &auth_header)
            // Core Artipie import headers (must match Java CLI)
            .header("X-Artipie-Repo-Type", &task.repo_type)
            .header("X-Artipie-Idempotency-Key", task.idempotency_key())
            .header("X-Artipie-Artifact-Name", task.file_path.file_name().unwrap().to_string_lossy().to_string())
            .header("X-Artipie-Artifact-Version", "")  // TODO: Extract from metadata
            .header("X-Artipie-Artifact-Owner", "admin")  // TODO: Make configurable
            .header("X-Artipie-Artifact-Size", task.size.to_string())
            .header("X-Artipie-Artifact-Created", task.created.to_string())
            .header("X-Artipie-Checksum-Mode", policy.as_str())
            .timeout(Duration::from_secs(args.timeout))
            // Provide Content-Length to avoid server-side temp spooling
            .header(reqwest::header::CONTENT_LENGTH, task.size.to_string())
            .body(body);

        // Optionally attach checksum headers when present
        if let Some(v) = md5.as_ref() {
            request = request.header("X-Artipie-Checksum-Md5", v);
        }
        if let Some(v) = sha1.as_ref() {
            request = request.header("X-Artipie-Checksum-Sha1", v);
        }
        if let Some(v) = sha256.as_ref() {
            request = request.header("X-Artipie-Checksum-Sha256", v);
        }

        match request.send().await {
            Ok(response) => {
                let status = response.status();
                debug!("Response status {} for {}", status, task.relative_path);
                
                if status.is_success() {
                    let upload_status = if status.as_u16() == 201 {
                        UploadStatus::Success
                    } else {
                        UploadStatus::Already
                    };
                    progress.mark_completed(task, &upload_status).await?;
                    return Ok(upload_status);
                } else if status.as_u16() == 409 {
                    let body = response.text().await.unwrap_or_default();
                    let quarantine_status = UploadStatus::Quarantined(body.clone());
                    progress.mark_completed(task, &quarantine_status).await?;
                    warn!("Quarantined: {} - {}", task.relative_path, body);
                    return Ok(quarantine_status);
                } else if status.as_u16() >= 500 && attempt < args.max_retries {
                    let body = response.text().await.unwrap_or_default();
                    warn!(
                        "Retry {}/{} for {} (HTTP {}): {}",
                        attempt, args.max_retries, task.relative_path, status, body
                    );
                    let backoff = Duration::from_millis(1000 * 2u64.pow(attempt - 1));
                    tokio::time::sleep(backoff).await;
                    continue;
                } else {
                    let body = response.text().await.unwrap_or_default();
                    let error_msg = format!("HTTP {}: {}", status, body);
                    error!("Failed: {} - {}", task.relative_path, error_msg);
                    let failed_status = UploadStatus::Failed(error_msg);
                    progress.mark_completed(task, &failed_status).await?;
                    return Ok(failed_status);
                }
            }
            Err(e) if attempt < args.max_retries => {
                warn!(
                    "Retry {}/{} for {}: {}",
                    attempt, args.max_retries, task.relative_path, e
                );
                let backoff = Duration::from_millis(1000 * 2u64.pow(attempt - 1));
                tokio::time::sleep(backoff).await;
            }
            Err(e) => {
                let error_msg = format!("Request failed: {}", e);
                error!("Failed: {} - {}", task.relative_path, error_msg);
                let failed_status = UploadStatus::Failed(error_msg);
                progress.mark_completed(task, &failed_status).await?;
                return Ok(failed_status);
            }
        }
    }

    let failed_status = UploadStatus::Failed("Max retries exceeded".to_string());
    progress.mark_completed(task, &failed_status).await?;
    Ok(failed_status)
}

fn detect_repo_type_from_dir(dir_name: &str) -> String {
    let lower = dir_name.to_lowercase();
    match lower.as_str() {
        "maven" => "maven".to_string(),
        "gradle" => "gradle".to_string(),
        "npm" => "npm".to_string(),
        "pypi" => "pypi".to_string(),
        "nuget" => "nuget".to_string(),
        "docker" | "oci" => "docker".to_string(),
        "composer" => "php".to_string(),
        "go" => "go".to_string(),
        "debian" => "deb".to_string(),
        "helm" => "helm".to_string(),
        "rpm" => "rpm".to_string(),
        "files" | "generic" => "file".to_string(),
        _ => {
            // Fallback: try to detect from directory name
            if lower.contains("maven") {
                "maven".to_string()
            } else if lower.contains("npm") {
                "npm".to_string()
            } else if lower.contains("docker") {
                "docker".to_string()
            } else {
                "file".to_string()
            }
        }
    }
}

fn collect_tasks(export_dir: &Path) -> Result<Vec<UploadTask>> {
    info!("Scanning for artifacts in {:?}...", export_dir);
    let mut tasks = Vec::new();
    let start = Instant::now();

    for entry in WalkDir::new(export_dir)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_file())
    {
        let path = entry.path();
        
        // Skip checksum files
        let path_str = path.to_string_lossy();
        if path_str.ends_with(".md5")
            || path_str.ends_with(".sha1")
            || path_str.ends_with(".sha256")
            || path_str.ends_with(".sha512")
        {
            continue;
        }

        let metadata = entry.metadata()?;
        let size = metadata.len();

        let relative = path.strip_prefix(export_dir)
            .with_context(|| format!("Failed to strip prefix from {:?}", path))?;
        let components: Vec<_> = relative.components().collect();
        
        // Need at least 3 components: type/repo-name/artifact-path
        if components.len() < 3 {
            continue;
        }

        // First component is the repository type (Maven, npm, Debian, etc.)
        let repo_type_dir = components[0].as_os_str().to_string_lossy().to_string();
        
        // Second component is the repository name
        let repo_name = components[1].as_os_str().to_string_lossy().to_string();
        
        // Remaining components form the artifact path
        let relative_path = components[2..]
            .iter()
            .map(|c| c.as_os_str().to_string_lossy())
            .collect::<Vec<_>>()
            .join("/");

        if relative_path.is_empty() {
            continue;
        }

        // Detect repo type from directory name
        let repo_type = detect_repo_type_from_dir(&repo_type_dir);

        let created = metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        tasks.push(UploadTask {
            repo_name,
            repo_type,
            relative_path,
            file_path: path.to_path_buf(),
            size,
            created,
        });

        if tasks.len() % 100000 == 0 {
            info!("  Found {} artifacts...", tasks.len());
        }
    }

    let elapsed = start.elapsed();
    info!(
        "Found {} total artifacts in {:.2}s",
        tasks.len(),
        elapsed.as_secs_f64()
    );
    Ok(tasks)
}

fn collect_retry_tasks(export_dir: &Path, failures_dir: &Path, completed: &HashSet<String>) -> Result<Vec<UploadTask>> {
    info!("Collecting failed uploads from {:?}...", failures_dir);
    let mut tasks = Vec::new();
    let mut seen = HashSet::new();
    
    if !failures_dir.exists() {
        warn!("Failures directory does not exist: {:?}", failures_dir);
        return Ok(tasks);
    }
    
    // Read all *-failures.log files (Java format) or *.txt files (Rust format)
    for entry in fs::read_dir(failures_dir)? {
        let entry = entry?;
        let path = entry.path();
        
        if !path.is_file() {
            continue;
        }
        
        let filename = path.file_name().unwrap().to_string_lossy();
        
        // Extract repo name from filename
        let repo_name = if filename.ends_with("-failures.log") {
            // Java format: repo-name-failures.log
            filename.trim_end_matches("-failures.log").to_string()
        } else if filename.ends_with(".txt") {
            // Rust format: repo-name.txt
            filename.trim_end_matches(".txt").to_string()
        } else {
            continue;
        };
        
        // Read failure log
        let file = File::open(&path)?;
        let reader = BufReader::new(file);
        
        for line in reader.lines() {
            let line = line?;
            if line.trim().is_empty() {
                continue;
            }
            
            // Parse line: "relative/path|error message"
            let relative_path = if let Some(sep_idx) = line.find('|') {
                &line[..sep_idx]
            } else {
                &line
            };
            
            let key = format!("{}|{}", repo_name, relative_path);
            if !seen.insert(key.clone()) {
                continue; // Skip duplicates
            }
            
            if completed.contains(&key) {
                continue; // Skip already completed
            }
            
            // Try to find the file in export_dir
            // Search under Type/repo-name/path structure
            let mut found = false;
            for type_entry in fs::read_dir(export_dir)? {
                let type_entry = type_entry?;
                if !type_entry.path().is_dir() {
                    continue;
                }
                
                let candidate = type_entry.path().join(&repo_name).join(relative_path);
                if candidate.is_file() {
                    // Found the file! Create upload task
                    let metadata = fs::metadata(&candidate)?;
                    let size = metadata.len();
                    
                    let repo_type_dir = type_entry.file_name().to_string_lossy().to_string();
                    let repo_type = detect_repo_type_from_dir(&repo_type_dir);
                    
                    let created = metadata
                        .modified()
                        .ok()
                        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                        .map(|d| d.as_secs())
                        .unwrap_or(0);
                    
                    tasks.push(UploadTask {
                        repo_name: repo_name.clone(),
                        repo_type,
                        relative_path: relative_path.to_string(),
                        file_path: candidate,
                        size,
                        created,
                    });
                    
                    found = true;
                    break;
                }
            }
            
            if !found {
                warn!("Could not find failed file: {}/{}", repo_name, relative_path);
            }
        }
    }
    
    info!("Found {} failed uploads to retry", tasks.len());
    Ok(tasks)
}

async fn write_failure_log(
    failures_dir: &Path,
    repo_name: &str,
    relative_path: &str,
    message: &str,
) -> Result<()> {
    let failure_file = failures_dir.join(format!("{}.txt", repo_name));
    let mut file = tokio::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(failure_file)
        .await?;
    
    use tokio::io::AsyncWriteExt;
    file.write_all(format!("{}|{}\n", relative_path, message).as_bytes()).await?;
    file.flush().await?;
    Ok(())
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> Result<()> {
    let args = Args::parse();

    // Initialize logging
    let log_level = if args.verbose { "debug" } else { "info" };
    tracing_subscriber::fmt()
        .with_env_filter(log_level)
        .with_target(false)
        .with_thread_ids(true)
        .init();

    info!("Artipie Import CLI v{}", env!("CARGO_PKG_VERSION"));
    info!("Configuration:");
    info!("  Server: {}", args.url);
    info!("  Export dir: {:?}", args.export_dir);
    info!("  Batch size: {}", args.batch_size);
    info!("  Timeout: {}s", args.timeout);
    info!("  Max retries: {}", args.max_retries);
    info!("  Pool size: {}", args.pool_size);

    // Determine concurrency
    let concurrency = args.concurrency.unwrap_or_else(|| {
        let cpus = num_cpus::get();
        let default = std::cmp::max(32, cpus * 16);
        info!("Auto-detected {} CPU cores, using {} concurrent tasks", cpus, default);
        default
    });
    info!("  Concurrency: {}", concurrency);

    // Create failures directory
    fs::create_dir_all(&args.failures_dir)
        .with_context(|| format!("Failed to create failures directory: {:?}", args.failures_dir))?;

    // Initialize progress tracker
    let progress = Arc::new(ProgressTracker::new(args.progress_log.clone(), args.resume)?);

    // Collect tasks based on mode
    let mut tasks = if args.retry {
        // Retry mode: only collect failed uploads from failures directory
        info!("RETRY MODE: Collecting failed uploads from failures directory");
        let completed = progress.get_completed_keys().await;
        collect_retry_tasks(&args.export_dir, &args.failures_dir, &completed)?
    } else {
        // Normal mode: collect all tasks
        collect_tasks(&args.export_dir)?
    };

    if tasks.is_empty() {
        if args.retry {
            info!("No failed uploads to retry!");
        } else {
            info!("No artifacts found!");
        }
        return Ok(());
    }

    // Filter out completed tasks (only in resume mode, not retry mode)
    if args.resume && !args.retry {
        info!("Filtering completed tasks...");
        let initial_count = tasks.len();
        let mut filtered = Vec::new();
        for task in tasks {
            if !progress.is_completed(&task.idempotency_key()).await {
                filtered.push(task);
            }
        }
        tasks = filtered;
        info!(
            "Skipped {} completed tasks, {} remaining",
            initial_count - tasks.len(),
            tasks.len()
        );
    }

    if tasks.is_empty() {
        info!("All tasks already completed!");
        return Ok(());
    }

    if args.dry_run {
        info!("DRY RUN - Would process {} tasks", tasks.len());
        return Ok(());
    }

    // Calculate total size
    let total_size: u64 = tasks.iter().map(|t| t.size).sum();
    info!(
        "Total size: {:.2} GB ({} bytes)",
        total_size as f64 / 1024.0 / 1024.0 / 1024.0,
        total_size
    );

    // Create HTTP client with optimized settings
    let client = Client::builder()
        .pool_max_idle_per_host(args.pool_size)
        .pool_idle_timeout(Duration::from_secs(90))
        .timeout(Duration::from_secs(args.timeout))
        .tcp_keepalive(Duration::from_secs(60))
        .build()
        .context("Failed to create HTTP client")?;

    // Setup progress bars
    let multi_progress = MultiProgress::new();
    let main_pb = multi_progress.add(ProgressBar::new(tasks.len() as u64));
    main_pb.set_style(
        ProgressStyle::default_bar()
            .template("[{elapsed_precise}] {bar:40.cyan/blue} {pos}/{len} ({percent}%) {msg}")?
            .progress_chars("=>-"),
    );

    let stats_pb = multi_progress.add(ProgressBar::new(0));
    stats_pb.set_style(ProgressStyle::default_bar().template("{msg}")?);

    // Start stats updater
    let progress_clone = progress.clone();
    let stats_pb_clone = stats_pb.clone();
    let start_time = Instant::now();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(5)).await;
            let (success, already, failed, quarantine, bytes) = progress_clone.get_stats();
            let elapsed = start_time.elapsed().as_secs_f64();
            let rate = (success + already) as f64 / elapsed;
            let mb_per_sec = (bytes as f64 / 1024.0 / 1024.0) / elapsed;
            stats_pb_clone.set_message(format!(
                "✓ {} | ⊙ {} | ✗ {} | ⚠ {} | {:.1} files/s | {:.2} MB/s",
                success, already, failed, quarantine, rate, mb_per_sec
            ));
        }
    });

    info!("\nStarting upload: {} tasks", tasks.len());

    // Create summary tracker for per-repository statistics
    let summary = Arc::new(SummaryTracker::new());

    // Process in batches with concurrency limit
    let semaphore = Arc::new(Semaphore::new(concurrency));

    for (batch_num, batch) in tasks.chunks(args.batch_size).enumerate() {
        debug!("Processing batch {}/{}", batch_num + 1, (tasks.len() + args.batch_size - 1) / args.batch_size);
        
        let futures = batch.iter().map(|task| {
            let client = client.clone();
            let task = task.clone();
            let args_clone = args.clone();
            let progress_clone = progress.clone();
            let summary_clone = summary.clone();
            let semaphore = semaphore.clone();
            let main_pb = main_pb.clone();

            async move {
                let _permit = semaphore.acquire().await.unwrap();
                let result = upload_file(&client, &task, &args_clone, &progress_clone).await;
                main_pb.inc(1);
                
                // Update summary tracker
                if let Ok(ref status) = result {
                    match status {
                        UploadStatus::Success => summary_clone.mark_success(&task.repo_name, false).await,
                        UploadStatus::Already => summary_clone.mark_success(&task.repo_name, true).await,
                        UploadStatus::Failed(_) => summary_clone.mark_failed(&task.repo_name).await,
                        UploadStatus::Quarantined(_) => summary_clone.mark_quarantined(&task.repo_name).await,
                    }
                }
                
                // Log failures
                if let Ok(UploadStatus::Failed(ref msg)) | Ok(UploadStatus::Quarantined(ref msg)) = result {
                    if let Err(e) = write_failure_log(&args_clone.failures_dir, &task.repo_name, &task.relative_path, msg).await {
                        error!("Failed to write failure log: {}", e);
                    }
                }
                
                (task, result)
            }
        });

        let _results: Vec<_> = stream::iter(futures)
            .buffer_unordered(concurrency)
            .collect()
            .await;
    }

    main_pb.finish_with_message("Complete!");
    stats_pb.finish();

    // Final statistics
    let (success, already, failed, _quarantine, bytes) = progress.get_stats();
    let elapsed = start_time.elapsed();
    
    // Display per-repository summary table
    println!("\n{}", summary.render_table().await);
    
    println!("\n=== Overall Statistics ===");
    println!("Data uploaded: {:.2} GB", bytes as f64 / 1024.0 / 1024.0 / 1024.0);
    println!("Time elapsed:  {}", humantime::format_duration(elapsed));
    println!("Average rate:  {:.1} files/second", (success + already) as f64 / elapsed.as_secs_f64());
    println!("Throughput:    {:.2} MB/second", (bytes as f64 / 1024.0 / 1024.0) / elapsed.as_secs_f64());

    // Write detailed JSON report with per-repository stats
    summary.write_report(&args.report).await?;
    info!("Report written to {:?}", args.report);

    if failed > 0 {
        warn!("Some uploads failed. Check {:?} for details", args.failures_dir);
        std::process::exit(1);
    }

    Ok(())
}
