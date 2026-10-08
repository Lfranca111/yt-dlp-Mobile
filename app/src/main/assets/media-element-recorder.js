(function () {
  if (window.__ytdlpElementCapture) return;
  const state = {recorder: null, stream: null, queue: [], blobs: [], reading: false, stopped: false, userStopped: false, error: null};
  const response = value => JSON.stringify(value);
  function readNextChunk() {
    if (state.reading || !state.blobs.length) return;
    state.reading = true;
    const reader = new FileReader();
    reader.onload = () => {
      state.queue.push(String(reader.result).split(',')[1]);
      state.reading = false;
      readNextChunk();
    };
    reader.onerror = () => { state.error = 'Could not read a recording chunk.'; state.reading = false; };
    reader.readAsDataURL(state.blobs.shift());
  }
  function bestVideo() {
    return Array.from(document.querySelectorAll('video'))
      .filter(video => {
        const rect = video.getBoundingClientRect();
        return video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0 &&
          rect.width > 0 && rect.height > 0 && rect.right > 0 && rect.bottom > 0 &&
          rect.left < innerWidth && rect.top < innerHeight;
      })
      .sort((a, b) => {
        const score = v => {
          const r = v.getBoundingClientRect();
          const area = Math.max(0, Math.min(r.right, innerWidth) - Math.max(0, r.left)) *
            Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(0, r.top));
          return (v.paused ? 0 : 1e9) + (v.ended ? 0 : 1e8) + area * 10;
        };
        return score(b) - score(a);
      })[0];
  }
  window.__ytdlpElementCapture = {
    async start() {
      if (state.recorder && state.recorder.state !== 'inactive') return response({error: 'Already recording.'});
      try {
        const video = bestVideo();
        if (!video) throw Error('No visible, playing video element is ready. Start the stream in this browser first.');
        if (video.paused || video.ended) throw Error('Start playing the actual video before recording.');
        if (typeof video.captureStream !== 'function' || typeof MediaRecorder === 'undefined') {
          throw Error('This Android WebView does not support media-element recording. Update Android System WebView and retry.');
        }
        const stream = video.captureStream();
        if (!stream.getVideoTracks().length) throw Error('The player did not expose a video track.');
        if (!stream.getAudioTracks().length) await new Promise(resolve => setTimeout(resolve, 1200));
        const hasAudio = stream.getAudioTracks().length > 0;
        const formats = hasAudio
          ? ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm']
          : ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'];
        const mime = formats
          .find(type => MediaRecorder.isTypeSupported(type));
        if (!mime) throw Error('This Android WebView cannot record WebM from the video element.');
        state.queue = []; state.blobs = []; state.reading = false; state.stopped = false;
        state.userStopped = false; state.error = null;
        state.stream = stream;
        const options = {mimeType: mime, videoBitsPerSecond: 5000000};
        if (hasAudio) options.audioBitsPerSecond = 160000;
        const recorder = new MediaRecorder(stream, options);
        state.recorder = recorder;
        recorder.ondataavailable = event => {
          if (!event.data || !event.data.size) return;
          state.blobs.push(event.data);
          readNextChunk();
        };
        recorder.onerror = event => { state.error = event.error?.message || 'The player stopped recording.'; };
        recorder.onstop = () => {
          state.stopped = true;
          if (!state.userStopped && !state.error) {
            state.error = 'Android WebView ended recording before you pressed Stop. The player may have replaced its video element; wait for playback to settle, then retry.';
          }
        };
        recorder.start(250);
        return response({ok: true, videoTracks: stream.getVideoTracks().length,
          audioTracks: stream.getAudioTracks().length, width: video.videoWidth,
          height: video.videoHeight, muted: video.muted, mimeType: recorder.mimeType});
      } catch (error) {
        state.stream?.getTracks().forEach(track => track.stop());
        state.stream = null; state.recorder = null;
        return response({error: String(error.message || error)});
      }
    },
    poll() {
      return response({chunk: state.queue.shift() || '',
        done: state.stopped && !state.reading && state.blobs.length === 0 && state.queue.length === 0,
        error: state.error || ''});
    },
    stop() {
      state.userStopped = true;
      if (state.recorder && state.recorder.state !== 'inactive') {
        state.recorder.requestData();
        state.recorder.stop();
      }
      return response({ok: true});
    },
    discard() {
      state.userStopped = true;
      if (state.recorder && state.recorder.state !== 'inactive') state.recorder.stop();
      state.queue = []; state.blobs = [];
      return response({ok: true});
    }
  };
})();
