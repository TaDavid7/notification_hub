package com.david.notification_hub.canvas;

import com.david.notification_hub.notification_request.NotificationRequestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CanvasPoller {

    private final WebClient http;
    private final NotificationRequestController controller;
    private final String baseUrl;
    // contexts are already shaped like "course_<id>"
    private final List<String> contextCodes;

    public CanvasPoller(
            NotificationRequestController controller,
            WebClient.Builder builder,
            @Value("${canvas.baseUrl}") String baseUrl,
            @Value("${canvas.token}") String token,
            // read as String then split; robust against YAML/ENV variations
            @Value("${canvas.courseIds:}") String courseIdsRaw
    ) {
        this.controller = controller;
        this.baseUrl = baseUrl.endsWith("/api/v1") ? baseUrl : baseUrl + "/api/v1";
        this.http = builder
                .baseUrl(this.baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        List<String> ids = new ArrayList<>();
        if (courseIdsRaw != null && !courseIdsRaw.isBlank()) {
            String cleaned = courseIdsRaw.replaceAll("[\\[\\]\\s\"]", ""); // supports YAML array or "a,b"
            if (!cleaned.isBlank()) ids = Arrays.asList(cleaned.split(","));
        }
        this.contextCodes = ids.isEmpty()
                ? List.of()
                : ids.stream().filter(s -> !s.isBlank()).map(id -> "course_" + id).toList();

        System.out.println("Loaded courseIds=" + ids + " -> contexts=" + this.contextCodes);
    }

    @Scheduled(fixedDelayString = "${canvas.pollDelayMs:120000}", initialDelayString = "${canvas.initialDelayMs:5000}")
    public void tick() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // wider window for testing; you can revert to minusDays(1)/plusDays(7) later
        var startIso = now.minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        var endIso   = now.plusDays(7).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        System.out.println("\nCanvas poll tick --------------------------------");
        System.out.println("Contexts = " + contextCodes);
        System.out.println("Time window = " + startIso + " → " + endIso);

        // Avoid calling /announcements with empty contexts (Canvas returns an error object)
        var announcements = contextCodes.isEmpty()
                ? List.<Map<String,Object>>of()
                : fetchAnnouncements(contextCodes, startIso, endIso);

        var events       = fetchCalendar("event", contextCodes, startIso, endIso);
        var assignments  = fetchCalendar("assignment", contextCodes, startIso, endIso);

        System.out.println("Fetched: Announcements=" + announcements.size()
                + ", Events=" + events.size() + ", Assignments=" + assignments.size());

        createBothFromList(announcements, "announcement");
        createBothFromList(events,       "calendar:event");
        createBothFromList(assignments,  "calendar:assignment");
    }

    private void createBothFromList(List<Map<String,Object>> items, String type) {
        for (var it : items) {
            String title = s(it, "title");
            String url   = s(it, "html_url");
            if (title == null || title.isBlank()) continue;

            var body = "[" + type + "] " + title + (url != null && !url.isBlank() ? "\n" + url : "");
            System.out.println("→ Creating notifications for: " + title);

            try {
                // Slack
                var sDto = new NotificationRequestController.CreateNotification();
                sDto.title = title; sDto.body = body; sDto.priority = "normal"; sDto.channel = "SLACK";
                controller.create(sDto);

                // Small throttle to be gentle with webhooks (and avoid burst timeouts)
                sleepQuiet(600);

                // Discord
                var dDto = new NotificationRequestController.CreateNotification();
                dDto.title = title; dDto.body = body; dDto.priority = "normal"; dDto.channel = "DISCORD";
                controller.create(dDto);

                // Optional: throttle a bit between items too
                sleepQuiet(800);
            } catch (Exception ex) {
                System.out.println("!! Failed to create notifications for: " + title + " — " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ---------- Canvas fetches ----------
    public List<Map<String,Object>> fetchAnnouncements(List<String> ctx, String startIso, String endIso) {
        String path = "/announcements?per_page=50&start_date=" + startIso + "&end_date=" + endIso;
        for (String c : ctx) path += "&context_codes[]=" + c;
        return fetchAllPages(path);
    }

    public List<Map<String,Object>> fetchCalendar(String type, List<String> ctx, String startIso, String endIso) {
        String path = "/calendar_events?per_page=50&type=" + type + "&start_date=" + startIso + "&end_date=" + endIso;
        for (String c : ctx) path += "&context_codes[]=" + c;
        return fetchAllPages(path);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> fetchAllPages(String path) {
        List<Map<String,Object>> out = new ArrayList<>();
        String next = path;
        while (next != null) {
            var res = http.get().uri(next).exchangeToMono(cr -> cr.toEntity(List.class)).block();
            if (res == null) break;
            out.addAll(Optional.ofNullable(res.getBody()).orElseGet(List::of));
            next = parseNext(res.getHeaders().getFirst("Link"));
        }
        return out;
    }

    private static final Pattern LINK_REL = Pattern.compile("<([^>]+)>;\\s*rel=\"(\\w+)\"");
    private static String parseNext(String linkHeader) {
        if (linkHeader == null) return null;
        Matcher m = LINK_REL.matcher(linkHeader);
        Map<String,String> links = new HashMap<>();
        while (m.find()) {
            var url = URI.create(m.group(1));
            links.put(m.group(2), url.getPath() + (url.getQuery() == null ? "" : "?" + url.getQuery()));
        }
        return links.get("next");
    }

    private static String s(Map<String,Object> m, String k) {
        var v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
