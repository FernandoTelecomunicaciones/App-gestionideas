package com.fernando.ahora.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fernando.ahora.R
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType

/**
 * The minimal, title-less bar of the three tabs: only a trailing settings icon, so the designed headings
 * ("¿Qué toca ahora?", "Tareas"…) stay content (PRODUCT_SPEC §3.2, DD-3). 48 dp target.
 */
@Composable
fun TabTopBar(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(R.drawable.ic_settings, stringResource(R.string.cd_settings), onOpenSettings)
    }
}

/** Ajustes bar: back arrow + "Ajustes" (PRODUCT_SPEC §3.2). */
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(R.drawable.ic_arrow_back, stringResource(R.string.cd_back), onBack)
        Text(title, style = AhoraType.sheetTitle, color = Ahora.colors.text)
    }
}

@Composable
fun IconAction(resId: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(resId, contentDescription, tint = Ahora.colors.text)
    }
}
