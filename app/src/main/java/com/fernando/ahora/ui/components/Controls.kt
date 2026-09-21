package com.fernando.ahora.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fernando.ahora.R
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType

/** Keyboard / D-pad focus: 2 px accent outline, 2 dp outside the control (PRODUCT_SPEC §9.3, A11Y-06). */
@Composable
fun Modifier.focusRing(interactionSource: MutableInteractionSource): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val color = Ahora.colors.accent
    return if (!focused) {
        this
    } else {
        this.drawWithContent {
            drawContent()
            val w = 2.dp.toPx()
            val o = 2.dp.toPx() + w / 2
            drawRect(color, topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o), style = Stroke(w))
        }
    }
}

enum class ButtonKind { Primary, Secondary, Ghost }

/**
 * Square button. [alignStart] = flush-left label (the design's primary buttons); 48 dp minimum height.
 * Pressed state uses the accent ramp step, disabled is 45 % (PRODUCT_SPEC §9.3).
 */
@Composable
fun AhoraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    alignStart: Boolean = false,
) {
    val c = Ahora.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg: Color
    val fg: Color
    when (kind) {
        ButtonKind.Primary -> { bg = if (pressed) c.accentFillPressed else c.accentFill; fg = c.onAccentFill }
        ButtonKind.Secondary -> { bg = if (pressed) c.text.copy(alpha = 0.14f) else Color.Transparent; fg = c.text }
        ButtonKind.Ghost -> { bg = if (pressed) c.accentText.copy(alpha = 0.18f) else Color.Transparent; fg = c.accentText }
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .focusRing(source)
            .background(bg)
            .then(if (kind == ButtonKind.Secondary) Modifier.border(1.dp, c.controlOutline) else Modifier)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (kind == ButtonKind.Ghost) AhoraType.bodySmallStrong else AhoraType.bodyStrong,
            color = fg,
            textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
        )
    }
}

/** Text-only action ("+ Más opciones", "Completadas"): accent text, 48 dp tall touch target. */
@Composable
fun AhoraTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateDescription: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (stateDescription != null) this.stateDescription = stateDescription },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = AhoraType.bodySmallStrong, color = Ahora.colors.accentText)
    }
}

/** Lucide icon from the bundled vector drawables, tinted at the call site. */
@Composable
fun LucideIcon(
    resId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * 22 dp visual inside a 48 dp touch target. [onCheckedChange] null ⇒ read-only (a completed occurrence of a recurring
 * task: reopening it is refused, D-31), which still announces its state to TalkBack.
 */
@Composable
fun AhoraCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    contentDescription: String,
    stateLabel: String,
    modifier: Modifier = Modifier,
) {
    val c = Ahora.colors
    val source = remember { MutableInteractionSource() }
    val base = modifier
        .size(48.dp)
        .focusRing(source)
        .semantics { this.contentDescription = contentDescription; this.stateDescription = stateLabel }
    Box(
        modifier = if (onCheckedChange != null) {
            base.toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
        } else {
            base.semantics { role = Role.Checkbox }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .then(if (checked) Modifier.background(c.accentFill) else Modifier.border(BorderStroke(1.5.dp, c.controlOutline))),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) LucideIcon(R.drawable.ic_check, null, size = 16.dp, tint = c.onAccentFill)
        }
    }
}

/** Square 40×22 switch from the prototype, on `toggleable(Role.Switch)` (D-20). Track is 3:1 against the ground. */
@Composable
fun AhoraSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    stateOn: String,
    stateOff: String,
    modifier: Modifier = Modifier,
) {
    val c = Ahora.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .focusRing(source)
            // The visible label is a sibling Text; without a name TalkBack would read only "Activado, interruptor" (GC-08).
            // Same order as AhoraCheckbox (semantics before toggleable): that is the arrangement verified on the device.
            .semantics {
                contentDescription = label
                stateDescription = if (checked) stateOn else stateOff
            }
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 40.dp, height = 22.dp).background(if (checked) c.accentFill else c.controlOutline)) {
            Box(Modifier.offset(x = if (checked) 21.dp else 3.dp, y = 3.dp).size(16.dp).background(Color.White))
        }
    }
}

/** P1 filled · P2 1 px outline · P3 neutral fill. Text is always shown: never colour alone (A11Y-01). */
@Composable
fun PriorityTag(priority: Priority, modifier: Modifier = Modifier) {
    val c = Ahora.colors
    val fill: Color
    val fg: Color
    val border: Color?
    when (priority) {
        Priority.P1 -> { fill = c.p1Fill; fg = c.p1Text; border = null }
        Priority.P2 -> { fill = Color.Transparent; fg = c.accentText; border = c.accent }
        Priority.P3 -> { fill = c.p3Fill; fg = c.p3Text; border = null }
    }
    Box(
        modifier = modifier
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border) else Modifier)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(priority.name, style = AhoraType.captionStrong, color = fg)
    }
}

/** Small caps section header ("Tus 3 de hoy", "Sin decidir todavía"). */
@Composable
fun SectionKicker(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, style = AhoraType.kicker, color = Ahora.colors.textSecondary)
}

/**
 * Square chip; selected = accent fill + white text (4.7:1), unselected = 3:1 outline. Visual is 32 dp inside a
 * 48 dp touch target. [role] `RadioButton` for single choice.
 */
@Composable
fun AhoraChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    small: Boolean = false,
    fillWidth: Boolean = false,
) {
    val c = Ahora.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg = when {
        selected && pressed -> c.accentFillPressed
        selected -> c.accentFill
        pressed -> c.text.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxSize() else Modifier)
                .focusRing(source)
                .background(bg)
                .border(1.dp, if (selected) c.accentFill else c.controlOutline)
                .padding(horizontal = if (small) 10.dp else 13.dp, vertical = if (small) 6.dp else 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = if (small) AhoraType.captionStrong else AhoraType.labelStrong,
                color = if (selected) c.onAccentFill else c.text,
            )
        }
    }
}
