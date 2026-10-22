package com.tue.nightcam.record

import androidx.lifecycle.LifecycleService

/**
 * Chủ sở hữu duy nhất của camera + MediaRecorder trong một phiên quay.
 * Đặc tả đầy đủ: docs/PLAN.md mục 4 và docs/TASKS.md task T2.
 */
class RecordingService : LifecycleService()
