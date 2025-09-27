# N-Relay

NotificationRelay is a lightweight app designed to relay notifications automatically via HTTP requests.

It enables you to share notifications with others without giving them direct access to the original source.

Perfect for automating alerts, integrations, or notifications across systems quickly and securely.

## Basic usage

Let's say you want to forward Telegram notifications from Mike to webhook.site.

With such a config in the app:
```json
[
  {
    "targetApp": "org.telegram.messenger",
    "titlePattern": "Mike",
    "url": "https://webhook.site/2ec42559-9596-4d44-9324-fc27d30acaba"
  }
]
```
you will receive payloads as follows:
```json
{
  "app": "org.telegram.messenger",
  "title": "Mike",
  "content": "Teapot is not working",
  "timestamp": 1758938473854
}
```

## Advanced usage

Let's say you want to post messages to different topics at ntfy.sh after Wise transactions depending on currency.

With such a config in the app:
```json
[
  {
    "targetApp": "com.transferwise.android",
    "contentPattern": ".+ spent at .+",
    "extractionPattern": ".+ spent at .+",
    "url": "https://ntfy.sh/",
    "payloadMappings": {
      "([\\d.]{1,6}) ([A-Z]{3}) spent at (.+)\\. Tap.+": {
        "message": "{2} {1}",
        "title": "{3}",
        "topic": "{2}-2ec42559-9596-4d44-9324-fc27d30acaba"
      }
    }
  }
]
```
you will receive payloads as follows:
```json
{
  "message": "PLN 13.37",
  "title": "Grand Hotel Lodz",
  "topic": "PLN-2ec42559-9596-4d44-9324-fc27d30acaba"
}
```

If you want to also see the basic fields, add `"sendMetadata": true` to that config section.

## Known issues

1. SMS notifications can't be read because of the "sensitive notification content hidden" feature.
2. JSON parser handles big integers as floats. To send a message to Telegram, provide chat_id as a string.