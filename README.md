# Axoniq Platform Framework client

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.axoniq.platform/axoniq-platform-framework-client/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.axoniq.platform/axoniq-platform-framework-client)

Axoniq Platform superpowers your Axon Framework application with advanced monitoring and enabling easy access to actions
within the framework.

![Screenshot of the handler performance screen](.github/img/screenshot_handler_performance.png)

This repository contains the Open-Source connectors that your application will use through maven dependencies.
For actual configuration, please consult the setup instructions that will be provided by AxonIQ Console itself.

[You can visit Axoniq Platform here.](https://platform.axoniq.io)

## Spring Boot Starter

### application properties

All properties use the `axoniq.platform` prefix.

* `axoniq.platform.credentials` - this needs to be set, otherwise the auto configuration won't do anything. The correct
  value can be retrieved via the AxonIQ Console UI.
* `axoniq.platform.application-name` - the display name of the application in the UI. Defaults to the Spring Boot
  application name.
* `axoniq.platform.host` / `axoniq.platform.port` - the host and port to connect to. Defaults to
  `framework.console.axoniq.io:7000`.
* `axoniq.platform.secure` - whether the connection uses SSL/TLS. Defaults to `true`.
* `axoniq.platform.dlq-mode` - controls how much Dead Letter Queue data is exposed to the Console.
  One of `NONE` (default), `MASKED`, `LIMITED`, `FULL`. See the [Data sent to AxonIQ](#data-sent-to-axoniq) section.
* `axoniq.platform.dlq-diagnostics-whitelist` - when `dlq-mode=LIMITED`, the list of diagnostic keys that are included.
* `axoniq.platform.domain-event-access-mode` - controls how much domain event data is exposed to the Console.
  One of `NONE` (default), `PREVIEW_PAYLOAD_ONLY`, `LOAD_DOMAIN_STATE_ONLY`, `FULL`.
* `axoniq.platform.max-concurrent-management-tasks` - bounds the number of user-initiated operations (e.g. DLQ
  processing) that run concurrently. Defaults to `5`.
* `axoniq.platform.initial-delay` - initial delay in milliseconds before the client tries to connect. Defaults to `0`.

## Data sent to AxonIQ

Axoniq Platform is an [Axoniq](https://axoniq.io) SaaS product. Your application will periodically, or upon request, send
information to the servers of AxonIQ. Please check our [Privacy Policy](https://www.axoniq.io/legal/privacy-policy) and
[Data Processing Addendum](https://lp.axoniq.io/axoniq-data-processing-addendum) for the measures we implemented to
protect your data.

Reporting intervals are controlled by the server and can be paused remotely. The following categories of data are sent:

### Periodic telemetry

- **Event processor status** - name, token store identifier, processor mode, running and error state, segment count and
  capacity, plus per-segment details (segment id, mask, mergeable id, caught-up state, ingest/commit/processing latency,
  current and reset positions, error type and message).
- **Handler statistics** - message names, handling component names, invocation counts, failure counts, latency
  distributions, and correlation between incoming messages and messages dispatched by their handlers. Message payloads
  and identifiers are **not** sent.
- **Application metrics** - system and process CPU usage, system load average, live thread count, heap memory (used,
  committed, max), and command bus and query bus capacity and usage.
- **Heartbeat** - keep-alive signal only, no payload.

### Once per connection (setup handshake)

When your application connects, it sends a one-time snapshot of its configuration:

- Command bus, query bus and event store types, whether they are connected to Axon Server, their interceptor class
  names, and the configured serializers.
- Event processor configuration: processor type, message source type and parameters, batch sizes, thread counts,
  token store type, token claim intervals, error handlers, and interceptors.
- Approximate event store size (the latest event store position).
- Axon Framework version and the versions of all Axon Framework and AxonIQ modules present on the classpath.
- Registered upcasters (class names only).
- Supported client features (thread dump, direct logging, license entitlement, DLQ mode, domain event access mode).

### On user request (initiated from the Console UI)

These flows are triggered by a user in the Console and are either disabled by default or carry no message content:

- **Dead Letter information** - disabled by default. When enabled, can include the message name, cause type and
  message, enqueued and last-touched timestamps, diagnostics map, sequence identifier, processing group, and optionally
  the message payload. Controlled by `axoniq.platform.dlq-mode`:
  - `NONE` (default) - list actions return empty; only counts are visible.
  - `MASKED` - limited data, sensitive fields masked, sequence identifier hashed with SHA-256.
  - `LIMITED` - payload never sent; diagnostics filtered by `dlq-diagnostics-whitelist`; sequence identifier not hashed.
  - `FULL` - full DLQ data including payload.
- **Domain events by entity** - disabled by default. When enabled, can return event metadata (sequence, timestamp,
  payload type), and optionally the payload and/or reconstructed aggregate state. Controlled by
  `axoniq.platform.domain-event-access-mode`:
  - `NONE` (default) - payload hidden, state loading disabled.
  - `PREVIEW_PAYLOAD_ONLY` - payload visible, state loading disabled.
  - `LOAD_DOMAIN_STATE_ONLY` - payload hidden, state loading enabled.
  - `FULL` - payload visible, state loading enabled.
- **Thread dumps** - full stack traces of all live threads, with thread names and a timestamp.
- **Event processor control** - start, stop, reset (head / tail / from timestamp), split, merge, release and claim
  segments. These are control actions; no application data is sent as part of the request.

### Received from AxonIQ

For completeness, the Console server also sends control messages to the client: updated reporting intervals, client
status updates (e.g. provisioned / using credits / blocked), signals to pause or resume reporting, log directives, and
license information. These do not cause additional application data to be collected.

### Summary of privacy-sensitive toggles

Message payloads never leave your application unless you explicitly opt in:

| Feature | Property | Default | Opt-in values |
| --- | --- | --- | --- |
| DLQ inspection | `axoniq.platform.dlq-mode` | `NONE` | `MASKED`, `LIMITED`, `FULL` |
| Domain event inspection | `axoniq.platform.domain-event-access-mode` | `NONE` | `PREVIEW_PAYLOAD_ONLY`, `LOAD_DOMAIN_STATE_ONLY`, `FULL` |

## How to Release

1. Run `mvn versions:set -DnewVersion=1.x.x` to update the version in the pom files
2. Commit and push the change
3. Wait for the release to be on Maven central and publish it
4. Create a Release at the current commit
5. Run `mvn versions:set -DnewVersion=1.x.x-SNAPSHOT`
6. Commit and push the new development version
