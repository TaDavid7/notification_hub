-- Canvas has been removed from the app.
--
-- Canvas never had tables or columns of its own: CanvasPoller enqueued its items into
-- notification_requests like any other producer, tagged with external_source
-- 'canvas:announcement', 'canvas:calendar:event' or 'canvas:calendar:assignment'.
-- Removing Canvas from the schema therefore means removing those rows.
--
-- This also catches rows posted by hand through the REST API with a 'canvas:' source,
-- since nothing distinguishes them from the poller's.
--
-- delivery_logs.request_id is ON DELETE CASCADE (V2), so each row's delivery history
-- goes with it. Irreversible by design. Rows from before V5 have a NULL
-- external_source and are left alone.

DELETE FROM notification_requests
 WHERE external_source LIKE 'canvas:%';
