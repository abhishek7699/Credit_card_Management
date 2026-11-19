-- Before this change, every record marked SENT came from a log-only gateway: nothing
-- left the service. Relabel them LOGGED and clear sent_at so reports stop claiming
-- delivery that never happened. New real sends write SENT.
UPDATE notification_records
   SET status = 'LOGGED',
       sent_at = NULL
 WHERE status = 'SENT';
