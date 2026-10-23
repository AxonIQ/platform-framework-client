# Workflow reporting

Workflow reporting requires Axoniq Framework 5.4, a persistent `TokenStore`, a JDBC
`DataSource` (PostgreSQL or H2), JSON event conversion, and an Inspector supporting
`workflow-telemetry-v1`. The workflow engine remains an optional client dependency.

For Spring Boot applications that already use workflows, enable reporting with:

```properties
axoniq.platform.workflow-source-id=orders-production-events
```

The source identifier names the event store being read. Keep it stable across
restarts and identical across replicas reading that store. Different stores need
different identifiers. Do not use a hostname or pod identifier. All replicas must
share the reporting token store and history database. Use a dedicated reporting
database or schema per monitored application.

For explicit configuration, register
`PlatformWorkflowTelemetry.module(sourceId, dataSource)` as an event processor
module. It uses the application's ambient event source, token store and unit of
work factory. The first run reads from the beginning; initial counts are partial
until it catches up. Global sequence tracking tokens, including replay wrappers,
are supported. Gap-aware and multi-source tokens are rejected rather than used
as approximate event positions.

`PlatformHistoryProjector` delegates state evolution to the framework's
`WorkflowHistoryProjector`, following the history adapter in the order fulfilment
sample. Its local `platform_workflow_history_event` table retains the events needed
to restore state after restart. Creation of that table requires DDL privileges.
The journal includes local event payloads and metadata; it is not uploaded. A cache
of up to 128 workflow histories avoids replaying the journal for each update.
History is rehydrated after restart, cache eviction or replay. Local journal
retention must preserve histories needed by the reporting token; automatic local
history deletion is not provided in this initial implementation.

The adapter uploads only event identifiers, projected workflow identity/version,
step names, statuses, timestamps and primitive names. A terminal history also
uploads its observed step names. Business payloads, results, exception details
and arbitrary metadata stay local. Definition observations accumulate across
executions: one completed execution discovers its own path, not unexecuted
branches, control-flow edges or source code.

The reporting processor completes an event only after Inspector acknowledges a
durable write. Disconnects, paused reporting and missing acknowledgements fail
processing and leave the token available for redelivery. Inspector deduplicates
by environment/application/source/workflow/event, including across replicas.
This processor is independent of the workflow engine and the application's own
history processor.

The adapter currently uses framework history APIs marked `@Internal`, pinned to
5.4. Test their behaviour when upgrading the framework. Release the matching
client API and deploy Inspector before enabling reporting in applications.
