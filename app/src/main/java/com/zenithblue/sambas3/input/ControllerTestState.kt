package com.zenithblue.sambas3.input

data class TestPhysicalInput(
    val keyCode: Int,
    val label: String,
    val action: String?,
)

data class TestInputDisplay(
    val physical: String,
    val action: String,
)

data class ControllerTestState(
    val deviceKey: String? = null,
    val pressedPhysicalKeys: Set<Int> = emptySet(),
    val pressedLogicalControls: Set<LogicalControl> = emptySet(),
    val unmappedPhysicalInputs: List<TestPhysicalInput> = emptyList(),
    val pad: LogicalPadState = LogicalPadState(),
    val lastInput: TestInputDisplay? = null,
)
