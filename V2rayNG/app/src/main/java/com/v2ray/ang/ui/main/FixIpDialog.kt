package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

@Composable
fun FixIpDialog(
    configs: List<FixedIpConfig>,
    selected: FixedIpConfig?,
    loading: Boolean,
    onUpdate: () -> Unit,
    onSelect: (FixedIpConfig?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.title_fix_ip))
                TextButton(onClick = onUpdate, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.title_fix_ip_update))
                    }
                }
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.title_fix_ip_none)) },
                        leadingContent = {
                            RadioButton(
                                selected = selected == null,
                                enabled = !loading,
                                onClick = { onSelect(null) }
                            )
                        },
                        modifier = Modifier.clickable(enabled = !loading) { onSelect(null) }
                    )
                }
                item { HorizontalDivider() }
                items(configs, key = { it.link }) { config ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = config.remark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = {
                            RadioButton(
                                selected = selected?.link == config.link,
                                enabled = !loading,
                                onClick = { onSelect(config) }
                            )
                        },
                        modifier = Modifier.clickable(enabled = !loading) { onSelect(config) }
                    )
                }
                if (configs.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.title_fix_ip_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}
