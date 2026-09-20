package com.romirmile.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romirmile.hermes.R
import com.romirmile.hermes.data.GatewayAdmin

/**
 * The agent's scheduled jobs (`GET /api/jobs`), with the same controls the CLI offers: run now,
 * pause/resume, delete. Nothing is invented here — these are the api_server's own job routes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(vm: HermesViewModel, onBack: () -> Unit) {
    val jobs by vm.jobs.collectAsState()
    val notice by vm.adminNotice.collectAsState()
    val loading by vm.adminLoading.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title), fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadJobs() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.cd_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading && jobs.isEmpty()) {
                Text(
                    stringResource(R.string.tasks_loading),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            notice?.let { message ->
                Text(
                    message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (jobs.isEmpty() && !loading) {
                Text(
                    stringResource(R.string.tasks_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(jobs, key = { it.id }) { job -> JobCard(job, vm, onDelete = { pendingDelete = job.id }) }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        val job = jobs.firstOrNull { it.id == target }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.tasks_delete_title)) },
            text = { Text(job?.name ?: target) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteJob(target)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun JobCard(job: GatewayAdmin.Job, vm: HermesViewModel, onDelete: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.tasks_state, job.state),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.tasks_schedule,
                    job.schedule + (job.repeatLabel?.let { " ($it)" } ?: "")
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            job.nextRunAt?.let {
                Text(
                    stringResource(R.string.tasks_next, it),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (job.lastRunAt != null) {
                Text(
                    stringResource(
                        R.string.tasks_last,
                        job.lastRunAt,
                        job.lastStatus ?: "?"
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            job.prompt?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    fontSize = 12.sp,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            job.lastError?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    fontSize = 11.sp,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.runJob(job.id) }) {
                    Text(stringResource(R.string.tasks_run), fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { if (job.paused) vm.resumeJob(job.id) else vm.pauseJob(job.id) }
                ) {
                    Text(
                        stringResource(
                            if (job.paused) R.string.tasks_resume else R.string.tasks_pause
                        ),
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.action_delete), fontSize = 12.sp)
                }
            }
        }
    }
}
