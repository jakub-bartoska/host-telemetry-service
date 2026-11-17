package com.hosttelemetry.simulator.cli

/** A command line or environment value that cannot be used; the message is shown to the user. */
class UsageException(message: String) : RuntimeException(message)
