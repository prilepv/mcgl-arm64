package org.lwjgl.opengl;

/** Existing Display counters extracted unchanged for the GLFW backend. */
final class MCGLDisplayMetrics {
	private static DisplayMode mcgl_windowed_mode;
	private static final boolean mcgl_graphics_profile = Boolean.getBoolean("mcgl.graphics.profile");
	private static final long mcgl_profile_period_nanos = 5000000000L;
	private static long mcgl_profile_window_start;
	private static long mcgl_previous_update_start;
	private static long mcgl_previous_update_end;
	private static long mcgl_update_count;
	private static long mcgl_frame_sample_count;
	private static long mcgl_frame_total_nanos;
	private static long mcgl_frame_max_nanos;
	private static long mcgl_outside_update_total_nanos;
	private static long mcgl_outside_update_sample_count;
	private static long mcgl_update_total_nanos;
	private static long mcgl_update_max_nanos;
	private static long mcgl_swap_count;
	private static long mcgl_swap_total_nanos;
	private static long mcgl_swap_max_nanos;
	private static long mcgl_display_list_count;
	private static long mcgl_frames_under_17_ms;
	private static long mcgl_frames_under_34_ms;
	private static long mcgl_frames_under_50_ms;
	private static long mcgl_frames_over_50_ms;

	static void mcglRecordDisplayList() {
		if ( mcgl_graphics_profile )
			mcgl_display_list_count++;
	}

	static long mcglProfileUpdateStarted() {
		if ( !mcgl_graphics_profile )
			return 0L;

		long now = System.nanoTime();
		if ( mcgl_profile_window_start == 0L ) {
			mcgl_profile_window_start = now;
			System.out.println("[MCGL Graphics] frame diagnostics active; rendering settings are unchanged.");
		}

		if ( mcgl_previous_update_start != 0L ) {
			long frame_nanos = now - mcgl_previous_update_start;
			mcgl_frame_sample_count++;
			mcgl_frame_total_nanos += frame_nanos;
			if ( frame_nanos > mcgl_frame_max_nanos )
				mcgl_frame_max_nanos = frame_nanos;
			if ( frame_nanos <= 16666667L )
				mcgl_frames_under_17_ms++;
			else if ( frame_nanos <= 33333333L )
				mcgl_frames_under_34_ms++;
			else if ( frame_nanos <= 50000000L )
				mcgl_frames_under_50_ms++;
			else
				mcgl_frames_over_50_ms++;
		}

		if ( mcgl_previous_update_end != 0L ) {
			mcgl_outside_update_total_nanos += now - mcgl_previous_update_end;
			mcgl_outside_update_sample_count++;
		}

		mcgl_previous_update_start = now;
		mcgl_update_count++;
		return now;
	}

	static long mcglProfileSwapFinished(long started) {
		if ( !mcgl_graphics_profile )
			return 0L;

		long elapsed = System.nanoTime() - started;
		mcgl_swap_count++;
		mcgl_swap_total_nanos += elapsed;
		if ( elapsed > mcgl_swap_max_nanos )
			mcgl_swap_max_nanos = elapsed;
		return elapsed;
	}

	static void mcglProfileUpdateFinished(long started, long limiter, long swap) {
		if ( !mcgl_graphics_profile )
			return;

		long now = System.nanoTime();
		long elapsed = now - started;
		mcgl_update_total_nanos += elapsed;
		if ( elapsed > mcgl_update_max_nanos )
			mcgl_update_max_nanos = elapsed;

		long profile_elapsed = now - mcgl_profile_window_start;
		if ( profile_elapsed >= mcgl_profile_period_nanos )
			mcglPrintAndResetGraphicsProfile(now, profile_elapsed);
		long frame_end = System.nanoTime();
		MCGLFrameProfiler.frame(frame_end, frame_end - started, limiter, swap, frame_end - now);
		mcgl_previous_update_end = System.nanoTime();
	}

	static void mcglPrintAndResetGraphicsProfile(long now, long profile_elapsed) {
		double seconds = profile_elapsed / 1000000000.0;
		double fps = seconds > 0.0 ? mcgl_update_count / seconds : 0.0;
		double frame_average = mcgl_frame_sample_count > 0L
			? mcgl_frame_total_nanos / (double)mcgl_frame_sample_count / 1000000.0 : 0.0;
		double outside_average = mcgl_outside_update_sample_count > 0L
			? mcgl_outside_update_total_nanos / (double)mcgl_outside_update_sample_count / 1000000.0 : 0.0;
		double update_average = mcgl_update_count > 0L
			? mcgl_update_total_nanos / (double)mcgl_update_count / 1000000.0 : 0.0;
		double swap_average = mcgl_swap_count > 0L
			? mcgl_swap_total_nanos / (double)mcgl_swap_count / 1000000.0 : 0.0;

		System.out.println(String.format(java.util.Locale.US,
			"[MCGL Graphics] %.1fs | FPS %.1f | frame avg/max %.2f/%.2f ms | game+render outside update %.2f ms | Display.update avg/max %.2f/%.2f ms | swap avg/max %.2f/%.2f ms (%d) | display lists %d | frames <=16.7/<=33.3/<=50/>50 ms: %d/%d/%d/%d",
			seconds, fps, frame_average, mcgl_frame_max_nanos / 1000000.0,
			outside_average, update_average, mcgl_update_max_nanos / 1000000.0,
			swap_average, mcgl_swap_max_nanos / 1000000.0, mcgl_swap_count,
			mcgl_display_list_count, mcgl_frames_under_17_ms, mcgl_frames_under_34_ms,
			mcgl_frames_under_50_ms, mcgl_frames_over_50_ms));

		mcgl_profile_window_start = now;
		mcgl_update_count = 0L;
		mcgl_frame_sample_count = 0L;
		mcgl_frame_total_nanos = 0L;
		mcgl_frame_max_nanos = 0L;
		mcgl_outside_update_total_nanos = 0L;
		mcgl_outside_update_sample_count = 0L;
		mcgl_update_total_nanos = 0L;
		mcgl_update_max_nanos = 0L;
		mcgl_swap_count = 0L;
		mcgl_swap_total_nanos = 0L;
		mcgl_swap_max_nanos = 0L;
		mcgl_display_list_count = 0L;
		mcgl_frames_under_17_ms = 0L;
		mcgl_frames_under_34_ms = 0L;
		mcgl_frames_under_50_ms = 0L;
		mcgl_frames_over_50_ms = 0L;
	}
}
