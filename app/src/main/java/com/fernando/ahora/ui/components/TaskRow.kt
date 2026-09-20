package com.fernando.ahora.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fernando.ahora.R
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType

/**
 * One list row (PRODUCT_SPEC §5.1–§5.3): [checkbox] · title (14 sp, 1–2 lines) · optional 11 sp [secondary] line ·
 * priority tag on the right. 56 dp minimum with a hairline underneath. The text block is ONE accessibility node
 * (title + priority + date) so TalkBack does not read three fragments (A11Y-04).
 *
 * @param checked null hides the checkbox (Bandeja rows: the decision is forced into the editor, INB-06).
 * @param onCheckedChange null with a non-null [checked] draws a read-only box (recurring occurrence, D-31).
 */
@Composable
fun TaskRow(
    title: String,
    modifier: Modifier = Modifier,
    priority: Priority? = null,
    secondary: String? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    checkedStateLabel: String? = null,
    singleLine: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val divider = Ahora.colors.divider
    val priorityText = priority?.let { stringResource(R.string.a11y_priority, it.name) }
    val spoken = listOfNotNull(title, priorityText, secondary).joinToString(", ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(divider, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            AhoraCheckbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                contentDescription = if (checked) title else stringResource(R.string.a11y_mark_done) + ": " + title,
                stateLabel = checkedStateLabel
                    ?: if (checked) stringResource(R.string.a11y_done) else stringResource(R.string.a11y_not_done),
            )
        }
        val source = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .focusRing(source)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .padding(start = if (checked != null) 4.dp else 0.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = AhoraType.body,
                    color = Ahora.colors.text,
                    maxLines = if (singleLine) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondary != null) {
                    Text(secondary, style = AhoraType.caption, color = Ahora.colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (priority != null) PriorityTag(priority)
        }
    }
}
