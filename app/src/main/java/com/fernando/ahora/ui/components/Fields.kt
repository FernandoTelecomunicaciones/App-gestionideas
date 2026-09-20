package com.fernando.ahora.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType

/**
 * Square input on the surface colour: 1 px control outline (>= 3:1) that becomes the accent while focused
 * (PRODUCT_SPEC §9.2). Value/onValueChange are plain state hoisted from a snapshot-state holder so typing stays
 * synchronous (ARCHITECTURE §9.2).
 */
@Composable
fun AhoraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = Ahora.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        textStyle = AhoraType.body.copy(color = c.text),
        placeholder = { Text(placeholder, style = AhoraType.body, color = c.textSecondary) },
        singleLine = singleLine,
        minLines = minLines,
        shape = RectangleShape,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.text,
            unfocusedTextColor = c.text,
            focusedContainerColor = c.surface,
            unfocusedContainerColor = c.surface,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.controlOutline,
            cursorColor = c.accent,
            focusedPlaceholderColor = c.textSecondary,
            unfocusedPlaceholderColor = c.textSecondary,
        ),
    )
}

/** Label above a field (12 sp, secondary) followed by its content, as in the prototype's `.ahora-sheet-field`. */
@Composable
fun FieldGroup(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = AhoraType.label, color = Ahora.colors.textSecondary)
        content()
    }
}

@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
