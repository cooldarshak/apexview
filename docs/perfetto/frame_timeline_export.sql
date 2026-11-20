-- Paste in Perfetto UI's Query tab for each captured session, then export CSV.
-- This includes actual SurfaceFlinger display frames and each app surface.
-- A display frame is NOT a unique decoded video frame; use the scene logs
-- and decoder SurfaceTexture timestamps for the 50fps video-specific side.
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
