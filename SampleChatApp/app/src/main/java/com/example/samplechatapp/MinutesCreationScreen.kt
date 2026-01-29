package com.example.samplechatapp

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState

@Composable
fun MinutesCreationScreen(mainViewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val isListening by mainViewModel.isListening.collectAsState()
    val recognizedText by mainViewModel.recognizedText.collectAsState()
    val isProcessing by mainViewModel.isProcessing.collectAsState()
    val showDeleteDialog by mainViewModel.showDeleteDialog.collectAsState()
    val showToast by mainViewModel.showToast.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding()
    ) {
        // ヘッダー
        Row(
            modifier = Modifier.padding(16.dp).statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ){
            // 右上にSpacerで空間を作り、削除ボタンを右端に配置
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { mainViewModel.showDeleteDialog(true) },
                enabled = recognizedText.isNotBlank()
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "削除",
                    tint = if (recognizedText.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
        }
        // 削除確認ダイアログ
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { mainViewModel.showDeleteDialog(false) },
                title = { Text("テキストの削除") },
                text = { Text("入力済みのテキストを削除します。よろしいですか？") },
                confirmButton = {
                    TextButton(onClick = { mainViewModel.deleteText() }) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { mainViewModel.showDeleteDialog(false) }) {
                        Text("キャンセル")
                    }
                }
            )
        }

        // 認識途中/確定のテキスト表示
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                Text(
                    text = if (recognizedText.isNotBlank()) recognizedText else stringResource(R.string.results_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // 画面下のトグルボタン（音声入力開始/停止）
        // ファイル化ボタン（条件付き表示）
        if (recognizedText.isNotBlank() && !isListening) {
            Button(
                onClick = {
                    if (isProcessing) return@Button
                    mainViewModel.createMinutesAndPdf(context)
                },
                enabled = !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(text = if (isProcessing) "処理中..." else "ファイル化")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (isListening) mainViewModel.stopListening() else mainViewModel.startListening(context)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (isListening) "音声入力を停止" else "音声入力を開始")
            }
        }
    }

    // トースト表示
    if (showToast != null) {
        LaunchedEffect(showToast) {
            Toast.makeText(context, showToast, Toast.LENGTH_LONG).show()
            mainViewModel.clearToast()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
fun MinutesCreationScreenPreview() {
    MinutesCreationScreen()
}