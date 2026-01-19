# PHP Composer Project with Private Artifact Repo

This sample shows how to:
1. Build a local Composer package.
2. Publish it to a private artifact repo on disk.
3. Require and use it in an app.

## Structure

```
php-composer-private-repo-sample/
├── app/
│   └── index.php
├── lib/
│   ├── composer.json
│   └── src/
│       └── Helper.php
├── private-repo/
│   └── artifacts/
├── scripts/
│   └── publish.sh
└── composer.json
```

## Usage

### Requirements
- PHP 8.0+
- Composer 2.x

### Steps

1. Publish the internal package:
```bash
./scripts/publish.sh
```

2. Install dependencies:
```bash
composer install
```

3. Run the app:
```bash
php app/index.php
```

Expected output:
```
Hello, Ayd!
```
