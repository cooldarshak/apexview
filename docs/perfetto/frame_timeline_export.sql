-- Paste in Perfetto UI's Query tab for each captured session, then export CSV.
-- Capture actual SurfaceFlinger display frames and app SurfaceView layers.
-- The main Activity window is HWUI (including clear TextureView scenes).
-- Protected SurfaceView video frames require independent SurfaceFlinger
-- classification. Correlate with decoder texture timestamps and audio clock
-- before asserting camera identity or sustained 50fps presentation.
SELECT
  ts,
  dur,
  display_frame_token,
  surface_frame_token,
  present_type,
  jank_type,
  COALESCE(layer_name, '') AS layer_name,
  COALESCE(process.name, '') AS process_name
FROM actual_frame_timeline_slice
LEFT JOIN process USING (upid)
WHERE dur >= 0
ORDER BY ts, display_frame_token;
