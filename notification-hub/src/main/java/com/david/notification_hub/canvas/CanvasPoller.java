package com.david.notification_hub.canvas;
//declares where file lives -> com/david/notification_hub/canvas/

import com.david.notification_hub.notification_request.NotificationRequestController;
//pulls in notification request controller class
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
import java.time.OffsetDateTime; //timestamp with a UTC offset
import java.time.ZoneOffset; //gives UTC
import java.time.format.DateTimeFormatter; //turns to string
import java.util.*; //yay data structures
import java.util.regex.Matcher; //reg expressions to parse header
import java.util.regex.Pattern;

@Component
public class CanvasPoller {

    private final WebClient http; //HTTP client instance
    private final NotificationRequestController controller; //ref to notifRequest controller
    private final String baseUrl; //canvas API root URL
    // contexts are already shaped like "course_<id>"
    private final List<String> contextCodes;

    //Spring calls new CanvasPoller()
    public CanvasPoller(
            NotificationRequestController controller, //passes controller
            WebClient.Builder builder,      //builder of webclient
            @Value("${canvas.baseUrl}") String baseUrl, //looks up canvas.baseUrl in application.yml
            @Value("${canvas.token}") String token,
            // read as String then split; robust against YAML/ENV variations
            @Value("${canvas.courseIds:}") String courseIdsRaw
    ) {
        this.controller = controller;
        this.baseUrl = baseUrl.endsWith("/api/v1") ? baseUrl : baseUrl + "/api/v1";
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

        System.out.println("Loaded courseIds=" + ids + " -> contexts=" + this.contextCodes);
    }

    @Scheduled(fixedDelayString = "${canvas.pollDelayMs:120000}", initialDelayString = "${canvas.initialDelayMs:5000}")
    // run method automatically. initialDelay = wait 5 seconds after app startup before first run, fixedDelay = wait 2 min after prev run finishes before starting next
    public void tick() {
        var now = OffsetDateTime.now(ZoneOffset.UTC); //current timestamp in UTC, var infer the type
        var startIso = now.minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME); //1 day ago
        var endIso   = now.plusDays(7).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME); //7 days ahead

        System.out.println("\nCanvas poll tick --------------------------------");
        System.out.println("Contexts = " + contextCodes);
        System.out.println("Time window = " + startIso + " → " + endIso);

        // Avoid calling /announcements with empty contexts (Canvas returns an error object)
        var announcements = contextCodes.isEmpty()
                ? List.<Map<String,Object>>of() //if no courses, use empty list
                : fetchAnnouncements(contextCodes, startIso, endIso); //api call for announcement

        //api calls for events and assignments
        var events       = fetchCalendar("event", contextCodes, startIso, endIso);
        var assignments  = fetchCalendar("assignment", contextCodes, startIso, endIso);

        System.out.println("Fetched: Announcements=" + announcements.size()
                + ", Events=" + events.size() + ", Assignments=" + assignments.size());

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
            System.out.println("→ Creating notifications for: " + title);

            try {
                String idStr = String.valueOf(it.get("id")); // Canvas item id

                // SLACK
                //Creates a DTO (data transfer object), class nested inside controller class
                var sDto = new NotificationRequestController.CreateNotification();
                sDto.title = title;
                sDto.body = body;
                sDto.priority = "normal";
                sDto.channel = "SLACK";
                sDto.externalSource = "canvas:" + type; //canvas:announcement
                sDto.externalId = idStr;
                controller.create(sDto);

                // Throttle
                sleepQuiet(600);

                // DISCORD
                var dDto = new NotificationRequestController.CreateNotification();
                dDto.title = title;
                dDto.body = body;
                dDto.priority = "normal";
                dDto.channel = "DISCORD";
                dDto.externalSource = "canvas:" + type;
                dDto.externalId = idStr;
                controller.create(dDto);

                // per-item delay
                sleepQuiet(800);
            } catch (Exception ex) {
                System.out.println("!! Failed to create notifications for: " + title + " — " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
    }

    //pauses current thread, ignores interruptedexception
    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
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
            //.block() - waits right here until it arrives
            var res = http.get().uri(next).exchangeToMono(cr -> cr.toEntity(List.class)).block();
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
