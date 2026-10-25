# actron-message

A process-wide, transient notification bus. Send `message(...)` from a machine command handler
or another application-owned coroutine. Pure StateChart decisions must not perform I/O.
No Store DSL scope is required.

```kotlin
import actron.message.Message
import actron.message.message
import actron.statechart.machine.CommandHandler

data class ContactSelected(val id: String) : Message
data class NotifySelection(val id: String)

val handler = CommandHandler<NotifySelection, Nothing> { command, _ ->
    message(ContactSelected(command.command.id))
}
```

`receiveMessages` is a runtime plugin. It subscribes at startup, before initial machine commands
run, and cancels its subscription when the runtime closes. Keep callbacks short. Convert incoming
notifications into declared machine inputs; receivers never write snapshots directly. Messages
are not replayed, so a receiver must already be subscribed when a notification is sent.

For recorded machine-to-machine interactions, use
[MachineGroup routes](../actron-statechart/README.md#running-a-machine). They preserve member,
message and delivery identities. This bus does not promise persisted delivery, request/reply
pairing or deterministic replay.

See [installation](../README.md#installation), [Message.kt](src/commonMain/kotlin/actron/message/Message.kt)
and [Plugin.kt](src/commonMain/kotlin/actron/message/Plugin.kt).
