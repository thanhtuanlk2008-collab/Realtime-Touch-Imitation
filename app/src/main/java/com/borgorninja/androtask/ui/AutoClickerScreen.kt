package com.borgorninja.androtask.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.borgorninja.androtask.MacroAccessibilityService
import com.borgorninja.androtask.data.MacroStep
import com.borgorninja.androtask.data.StepType
import com.borgorninja.androtask.viewmodel.MacroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoClickerScreen(nav: NavController, vm: MacroViewModel) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val centerX = metrics.widthPixels / 2f
    val centerY = metrics.heightPixels / 2f
    val steps = remember { mutableStateListOf<MacroStep>() }
    var interval by remember { mutableStateOf("500") }
    var repeats by remember { mutableStateOf("10") }
    var timerSeconds by remember { mutableStateOf("0") }
    var configName by remember { mutableStateOf("Auto Clicker sequence") }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("Auto Clicker", color = OnBg) },
                navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Targets & gestures", style = MaterialTheme.typography.titleMedium, color = OnBg)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    steps.add(MacroStep(macroId = 0L, stepIndex = steps.size, type = StepType.TAP,
                        x = centerX, y = centerY, duration = 100L))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add tap")
                }
                OutlinedButton(onClick = {
                    steps.add(MacroStep(macroId = 0L, stepIndex = steps.size, type = StepType.SWIPE,
                        x = centerX - 100f, y = centerY, x2 = centerX + 100f, y2 = centerY, duration = 500L))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add swipe")
                }
            }
            Text("Targets start near screen centre. Adjust their pixel coordinates below.",
                color = OnBgVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = configName, onValueChange = { configName = it },
                label = { Text("Configuration name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = interval, onValueChange = { interval = it.filter(Char::isDigit) },
                    label = { Text("Interval (ms)") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = repeats, onValueChange = { repeats = it.filter(Char::isDigit) },
                    label = { Text("Repeats (0 = infinite)") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            OutlinedTextField(value = timerSeconds, onValueChange = { timerSeconds = it.filter(Char::isDigit) },
                label = { Text("Stop after (seconds, 0 = off)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sequence (" + steps.size + ")", color = OnBg, modifier = Modifier.weight(1f))
                TextButton(onClick = { steps.clear() }) { Text("Clear all") }
            }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(steps, key = { index, step -> "${index}-${step.type}-${step.x}-${step.y}" }) { index, step ->
                    Card(colors = CardDefaults.cardColors(containerColor = CardDark)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("#" + (index + 1) + "  " + step.type, color = OnBg, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    steps.removeAt(index)
                                    steps.indices.forEach { i -> steps[i] = steps[i].copy(stepIndex = i) }
                                }) { Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(value = step.x.toInt().toString(),
                                    onValueChange = { v -> steps[index] = step.copy(x = v.toFloatOrNull() ?: 0f) },
                                    label = { Text("X") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(value = step.y.toInt().toString(),
                                    onValueChange = { v -> steps[index] = step.copy(y = v.toFloatOrNull() ?: 0f) },
                                    label = { Text("Y") }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                            if (step.type == StepType.SWIPE) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(value = step.x2.toInt().toString(),
                                        onValueChange = { v -> steps[index] = step.copy(x2 = v.toFloatOrNull() ?: 0f) },
                                        label = { Text("End X") }, modifier = Modifier.weight(1f), singleLine = true)
                                    OutlinedTextField(value = step.y2.toInt().toString(),
                                        onValueChange = { v -> steps[index] = step.copy(y2 = v.toFloatOrNull() ?: 0f) },
                                        label = { Text("End Y") }, modifier = Modifier.weight(1f), singleLine = true)
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (steps.isEmpty()) Toast.makeText(context, "Add at least one target", Toast.LENGTH_SHORT).show()
                    else {
                        val gap = interval.toLongOrNull()?.coerceAtLeast(0L) ?: 500L
                        val configured = steps.mapIndexed { i, s -> s.copy(stepIndex = i, delayBefore = if (i == 0) 0L else gap) }
                        vm.saveMacro(name = configName.ifBlank { "Auto Clicker sequence" },
                            description = "Auto Clicker targets",
                            loopCount = repeats.toIntOrNull()?.coerceAtLeast(1) ?: 10,
                            loopDelay = gap, speedMultiplier = 1f, steps = configured) {
                            Toast.makeText(context, "Configuration saved", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Save, null); Text("Save")
                }
                Button(onClick = {
                    if (MacroAccessibilityService.instance == null) {
                        Toast.makeText(context, "Enable Accessibility first", Toast.LENGTH_LONG).show()
                    } else if (steps.isEmpty()) {
                        Toast.makeText(context, "Add at least one target", Toast.LENGTH_SHORT).show()
                    } else {
                        val gap = interval.toLongOrNull()?.coerceAtLeast(0L) ?: 500L
                        val configured = steps.mapIndexed { i, s -> s.copy(stepIndex = i, delayBefore = if (i == 0) 0L else gap) }
                        val count = repeats.toIntOrNull() ?: 10
                        val timer = timerSeconds.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                        vm.runAutoClickerSteps(configured, if (count == 0) -1 else count, timer * 1000L)
                        Toast.makeText(context, "Sequence started", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null); Text("Start")
                }
            }
        }
    }
}
