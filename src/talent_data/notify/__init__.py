"""Personal Telegram notifications for the owner (M5.NOTIFY).

Four events, rendered in Russian and sent through the Telegram Bot API from the existing
workflows - no server of our own runs anywhere:

* a new game build appeared in the wago.tools index (never "the realms run it");
* a PREPARED OTA candidate (never a permission to publish);
* the active release of the signed public production catalog changed after verification
  (a re-signed catalog with the same active release is not a release);
* a preparation or check broke (an expected OUTDATED freshness answer is not a failure).

Every notification is deduplicated through durable state committed to the branch
``telegram-notify-state`` - not through the Actions cache or run artifacts - so repeated
runs of the same facts never spam. A first run records the current facts as the baseline
and sends nothing. A Telegram failure never changes the outcome of the preparation or
publication it reports on: the notify job shows its own status (``SENT`` / ``SKIPPED`` /
``FAILED``) and continues-on-error. Delivery is at-least-once: a send whose answer was
ambiguous (network timeout after retries) is reported as not sent and may repeat later.
"""
