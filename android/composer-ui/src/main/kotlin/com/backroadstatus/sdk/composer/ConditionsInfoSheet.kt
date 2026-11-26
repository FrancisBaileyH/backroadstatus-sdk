package com.backroadstatus.sdk.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.backroadstatus.sdk.core.Condition

/**
 * Explains each user-pickable road condition rating. Mirrors
 * `fragments/conditions.html :: condition-popup` on the web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConditionsInfoSheet(onDismiss: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Road Condition Ratings") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(Condition.userPickable) { condition ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConditionBadge(condition)
                    Text(
                        text = condition.description,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }
            item {
                Text(
                    text = "Note that gates, avalanches and snow lines should not qualify a road as impassable.",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun ConditionBadge(condition: Condition) {
    Surface(
        color = condition.color,
        shape = RoundedCornerShape(50),
        modifier = Modifier.background(condition.color, RoundedCornerShape(50)),
    ) {
        Text(
            text = condition.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = condition.chipForeground,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
