package com.example;

import com.google.common.base.Splitter;
import com.google.gson.Gson;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.util.Timeout;   // <-- add this
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        String csv = "alpha,beta,gamma";
        List<String> parts = Splitter.on(',').trimResults().splitToList(csv);

        String joined = StringUtils.join(parts, " | ");
        log.info("Joined: {}", joined);

        String body = Request.get("https://httpbin.org/json")
                .connectTimeout(Timeout.ofSeconds(3))
                .responseTimeout(Timeout.ofSeconds(3))
                .execute()
                .returnContent()
                .asString(StandardCharsets.UTF_8);

        Map<?,?> parsed = new Gson().fromJson(body, Map.class);
        log.info("Fetched keys: {}", parsed.keySet());
    }
}
