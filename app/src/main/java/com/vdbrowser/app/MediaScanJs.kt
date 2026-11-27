package com.vdbrowser.app

/** Page scripts that report video URLs through the "VDBridge" JavaScript interface. */
object MediaScanJs {

    /** Report every <video>/<source> URL currently in the DOM. */
    val SCRIPT = """
        (function() {
          try {
            var add = function(u) {
              if (u && u.indexOf('blob:') !== 0 && u.indexOf('data:') !== 0) {
                VDBridge.onMediaFound(u);
              }
            };
            document.querySelectorAll('video').forEach(function(v) {
              add(v.currentSrc); add(v.src);
              v.querySelectorAll('source').forEach(function(s) { add(s.src); });
            });
            document.querySelectorAll('source').forEach(function(s) { add(s.src); });
          } catch (e) {}
        })();
    """.trimIndent()

    /** Muted playback: many players only request the real video file once it starts. */
    val START_PLAYBACK = """
        (function() {
          try {
            document.querySelectorAll('video').forEach(function(v) {
              v.muted = true;
              var p = v.play();
              if (p && p.catch) p.catch(function() {});
            });
          } catch (e) {}
        })();
    """.trimIndent()
}
