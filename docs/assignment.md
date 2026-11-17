# Backend Home Assignment – Host Telemetry Aggregator

> Original assignment text, kept here for reference.

## The story

You develop a platform for device management. Our customers manage fleets of devices, often
thousands of them, and they expect to see what every device is doing.

Picture an operator on a Friday afternoon. A user complained that their service got slow
sometime yesterday. The operator opens the dashboard, picks the device, and wants to see
CPU, memory, network traffic, power draw and temperature over the last hour, day or week.
Was the CPU pegged? Did the device overheat and throttle? Did traffic spike? The charts must
load fast and tell the truth.

To make that possible, every device runs a small agent that streams its stats to a central
backend. That backend is what we want you to build.

## Your task

Build a Kotlin backend service, the **Host Telemetry Aggregator**, that has two sides.

1. **Ingestion:** devices connect over a WebSocket and stream a stats message roughly
   every 10 seconds. The service receives, validates and persists them.
2. **Charts API:** an HTTP API returns chart-ready time series for one stat of one device, for
   the last hour, day or week.

We don't have a fleet of devices to give you, so you also need to answer a question every real
backend team faces: how do you know it works? Generating realistic data and proving the
service holds up is part of the assignment.

## Interfaces

Each device sends a message like this over its WebSocket connection, about every 10
seconds:

```json
{
  "type": "stats",
  "deviceId": "host-0042",
  "timestamp": "2026-10-26T14:03:20Z",
  "stats": {
    "cpuUtilPct": 37.5,
    "memUtilPct": 62.1,
    "rxBps": 845200000,
    "txBps": 412800000,
    "powerW": 312.4,
    "temperatureC": 58.2
  }
}
```

The stats map is intentionally generic. Other device types may later send the same shape with
different keys.

The frontend needs chart data for one stat of one device at a time, as a series of [timestamp,
value] points covering the last hour, day or week.

A chart is a few hundred pixels wide, so returning tens of thousands of points is not useful. The
API design (endpoints, parameters, response format), how many points each range returns, and
how they are computed, is up to you.

## Requirements

- **Language:** Kotlin. Any framework and libraries you like.
- **Persistent storage:** data must survive a service restart. In-memory-only storage is not
  accepted. The choice of database is yours.
- **Scale:** design for thousands of devices, each sending every ~10 seconds, with a week
  of history queryable. Think about what that means for writes, storage size and query
  speed.
- **Runnable:** we should be able to start everything locally with one or two commands (e.g.
  Docker Compose).
- **Data generation:** provide a way to simulate many devices streaming plausible data, so
  we can run your service and see charts with real-looking values.
- **Testing:** show us how you know the service is correct and how it behaves under load.

Everything not listed here is your call: how you handle malformed messages, disconnects,
devices that go silent, late or out-of-order data, and anything else you think a production version
would need. You won't have time for all of it. Choosing what to do, and writing down what you
skipped, is part of the assignment.

## Time, tools and what to send

Plan for about **4 hours**. Please don't go far beyond that. We are more interested in your choices
than in a complete product.

Using AI coding assistants is expected and welcome. Use them the way you would at work. We
care about the result and your judgment, not who typed which line.

Please send us:

- A Git repository (link or archive) with the code and its commit history.
- A short README covering how to run the service and the simulator, your key design
  decisions, and what you would do next with more time.

In the follow-up interview we will walk through your solution together. Be ready to explain your
trade-offs, especially around storage and scale.
