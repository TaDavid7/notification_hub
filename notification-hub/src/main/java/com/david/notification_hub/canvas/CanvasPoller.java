package com.david.notification_hub.canvas;
//declares where file lives -> com/david/notification_hub/canvas/

import com.david.notification_hub.notification_request.NotificationIntakeService;
//the one entry point into the outbox, shared with the REST controller
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//logging, so these lines land in CloudWatch with a level and timestamp
import org.springframework.beans.factory.annotation.Value;
//allows to inject values into field parameters
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
//constants for http header names
import org.springframework.scheduling.annotation.Scheduled;
//method run on a timer
import org.springframework.stereotype.Component;
//@Component tells Spring to create an instance of the class and manage it
import org.springframework.web.reactive.function.client.WebClient;
//Spring HTTP client, make web requests

import java.net.URI; //class for parsing URLS into pieces
import java.time.Duration; //bounds the blocking Canvas calls
import java.time.OffsetDateTime; //timestamp with a UTC offset
import java.time.ZoneOffset; //gives UTC
import java.time.format.DateTimeFormatter; //turns to string
import java.util.*; //yay data structures
import java.util.function.Supplier; //lets safeFetch wrap a call without running it
import java.util.regex.Matcher; //reg expressions to parse header
import java.util.regex.Pattern;

@Component
public class CanvasPoller {

    private static final Logger log = LoggerFactory.getLogger(CanvasPoller.class);

    // An unreachable or hanging Canvas used to block this thread forever, because
    // .block() was called with no argument. One bad poll then never finished.
    private static final Duration CANVAS_TIMEOUT = Duration.ofSeconds(20);

    private final WebClient http; //HTTP client instance
    private final NotificationIntakeService intake; //writes rows into the outbox
    private final String baseUrl; //canvas API root URL
    // contexts are already shaped like "course_<id>"
    private final List<String> contextCodes;
    // false when Canvas isn't configured, so tick() stays a no-op instead of
    // firing requests at a base URL that doesn't exist
    private final boolean enabled;

    //Spring calls new CanvasPoller()
    public CanvasPoller(
            NotificationIntakeService intake, //enqueues, rather than sending inline
            WebClient.Builder builder,      //builder of webclient
            @Value("${canvas.baseUrl}") String baseUrl, //looks up canvas.baseUrl in application.yml
            @Value("${canvas.token}") String token,
            // read as String then split; robust against YAML/ENV variations
            @Value("${canvas.courseIds:}") String courseIdsRaw
    ) {
        this.intake = intake;
        //an unset CANVAS_BASE_URL arrives as "" (see the :- default in application.yml),
        //which would otherwise become the relative "/api/v1" and resolve to localhost:80
        String base = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = base.isBlank() || base.endsWith("/api/v1") ? base : base + "/api/v1";
        this.http = builder
                .baseUrl(this.baseUrl) //builds URL based on canvas in .yml and everything relative to this
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)  //canvas knows who with authorization by token
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE) //json
                .build(); //finish building

        List<String> ids = new ArrayList<>();
        if (courseIdsRaw != null && !courseIdsRaw.isBlank()) {
            String cleaned = courseIdsRaw.replaceAll("[\\[\\]\\s\"]", ""); // supports YAML array -> "a,b"
            if (!cleaned.isBlank()) ids = Arrays.asList(cleaned.split(",")); //splits on commans into array ["a", "b"]
        }
        this.contextCodes = ids.isEmpty()
                ? List.of() //if empty, then give empty list
                : ids.stream().filter(s -> !s.isBlank()).map(id -> "course_" + id).toList();
                //.filter(s...()) drops any empty entries
                //.map(id -> "course_" + id) transform each id into course_a, course_b
                //.toList() puts back into list

        //both halves are required: a base URL to call, and at least one course to ask about
        this.enabled = !this.baseUrl.isBlank() && !this.contextCodes.isEmpty();

        if (this.enabled) {
            log.info("Canvas polling enabled. courseIds={} -> contexts={}", ids, this.contextCodes);
        } else {
            log.info("Canvas polling disabled (set CANVAS_BASE_URL and CANVAS_COURSE_IDS to enable).");
        }
    }

    @Scheduled(fixedDelayString = "${canvas.pollDelayMs:120000}", initialDelayString = "${canvas.initialDelayMs:5000}")
    // run method automatically. initialDelay = wait 5 seconds after app startup before first run, fixedDelay = wait 2 min after prev run finishes before starting next
    public void tick() {
        if (!enabled) return; //nothing to poll, and no base URL to poll it from

        var now = OffsetDateTime.now(ZoneOffset.UTC); //current timestamp in UTC, var infer the type
        var startIso = now.minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME); //1 day ago
        var endIso   = now.plusDays(7).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME); //7 days ahead

        log.info("Canvas poll tick. contexts={} window={} -> {}", contextCodes, startIso, endIso);

        //contexts are guaranteed non-empty here, so Canvas won't hand back an error object
        //each fetch is isolated: an expired token returns 401 on all three, but a single
        //failing endpoint used to abort the entire day's poll before the others ran
        var announcements = safeFetch("announcements",
                () -> fetchAnnouncements(contextCodes, startIso, endIso));
        var events        = safeFetch("calendar:event",
                () -> fetchCalendar("event", contextCodes, startIso, endIso));
        var assignments   = safeFetch("calendar:assignment",
                () -> fetchCalendar("assignment", contextCodes, startIso, endIso));

        log.info("Canvas fetch complete. announcements={} events={} assignments={}",
                announcements.size(), events.size(), assignments.size());

        //give each batch to method that creates notifications
        createBothFromList(announcements, "announcement");
        createBothFromList(events,       "calendar:event");
        createBothFromList(assignments,  "calendar:assignment");
    }

    //creating notifications
    private void createBothFromList(List<Map<String,Object>> items, String type) {
        for (var it : items) { //each indiv canvas item, one json object as map
            String title = s(it, "title"); //puts field using s() helper defined at bottom
            String url   = s(it, "html_url");
            if (title == null || title.isBlank()) continue;

            //builds message [announcement] Midterm moved + newline with link or no link
            var body = "[" + type + "] " + title + (url != null && !url.isBlank() ? "\n" + url : "");
            //per-item, so debug: a busy poll would otherwise flood CloudWatch
            log.debug("Enqueueing notifications for: {}", title);

            try {
                String idStr = String.valueOf(it.get("id")); // Canvas item id
                String source = "canvas:" + type;            // canvas:announcement

                // Two rows, one per channel. The old code slept 600ms and 800ms
                // between these because each call sent a webhook inline; enqueueing
                // is a single INSERT, and the dispatcher paces the actual sends.
                intake.enqueue(title, body, "normal", "SLACK", source, idStr);
                intake.enqueue(title, body, "normal", "DISCORD", source, idStr);
            } catch (Exception ex) {
                //passing the exception last gives us the stack trace, which the
                //old println threw away
                log.error("Failed to enqueue notifications for: {}", title, ex);
            }
        }
    }

    /**
     * Runs one Canvas fetch, turning a failure into an empty list rather than an
     * exception that unwinds {@link #tick()}.
     */
    private List<Map<String,Object>> safeFetch(String what, Supplier<List<Map<String,Object>>> fetch) {
        try {
            return fetch.get();
        } catch (Exception ex) {
            log.error("Canvas fetch failed for {}; continuing with the other endpoints", what, ex);
            return List.of();
        }
    }

    // Canvas fetch
    public List<Map<String,Object>> fetchAnnouncements(List<String> ctx, String startIso, String endIso) {
        String path = "/announcements?per_page=50&start_date=" + startIso + "&end_date=" + endIso;
        for (String c : ctx) path += "&context_codes[]=" + c;
        //creates path to fetch from canvas the &context_codes[]=course_a is style on how Canvas acceps arrays
        return fetchAllPages(path);
    }

    public List<Map<String,Object>> fetchCalendar(String type, List<String> ctx, String startIso, String endIso) {
        String path = "/calendar_events?per_page=50&type=" + type + "&start_date=" + startIso + "&end_date=" + endIso;
        for (String c : ctx) path += "&context_codes[]=" + c;
        return fetchAllPages(path);
    }

    @SuppressWarnings("unchecked") //silences generic type info compiler can't verify
    private List<Map<String,Object>> fetchAllPages(String path) {
        List<Map<String,Object>> out = new ArrayList<>();
        String next = path;
        while (next != null) {
            //http.get() - get request
            //.uri(next) set the target URL
            //.exchangeToMono hands raw ClientREsponse to lambda as a List. mono is a value that arrives later
            //cr.toEntity(List.class) - deserialize body to ResponseEntity<List>
            //.block(timeout) - waits here, but gives up rather than hanging forever
            var res = http.get().uri(next).exchangeToMono(cr -> cr.toEntity(List.class))
                    .block(CANVAS_TIMEOUT);
            if (res == null) break;
            //use the body or an empty list if its null
            //List::of is a method ref for () -> List.of()
            out.addAll(Optional.ofNullable(res.getBody()).orElseGet(List::of));
            //read link header and extract next page URL
            next = parseNext(res.getHeaders().getFirst("Link"));
        }
        return out;
    }

    //A compiled regex, stored as a constant. Matches Canvas link header format <https://...?page=2>; rel="next"
    //group1 gets the URL, group2 is the relation word
    private static final Pattern LINK_REL = Pattern.compile("<([^>]+)>;\\s*rel=\"(\\w+)\"");
    //run regex over header
    private static String parseNext(String linkHeader) {
        if (linkHeader == null) return null;
        Matcher m = LINK_REL.matcher(linkHeader);
        Map<String,String> links = new HashMap<>();
        //advances to each successive match
        while (m.find()) {
            var url = URI.create(m.group(1));
            links.put(m.group(2), url.getPath() + (url.getQuery() == null ? "" : "?" + url.getQuery()));
        }
        return links.get("next");
    }

    //look up key k in map m, return null if absent, otherwise convert to string
    private static String s(Map<String,Object> m, String k) {
        var v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
