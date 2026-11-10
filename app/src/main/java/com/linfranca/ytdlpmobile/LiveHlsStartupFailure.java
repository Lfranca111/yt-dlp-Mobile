package com.linfranca.ytdlpmobile;

/** Safe startup diagnostics: request stage and response code, never an address or header value. */
final class LiveHlsStartupFailure extends IllegalStateException {
    final int status;
    final boolean retryable;

    LiveHlsStartupFailure(String stage, int status) {
        super(stage + (status == 200 ? " returned no usable data (HTTP 200)" :
            status > 0 ? " request failed (HTTP " + status + ")" : " could not be fetched or validated"));
        this.status = status;
        this.retryable = status <= 0 || status == 200 || status == 401 || status == 403 ||
            status == 404 || status == 408 || status == 410 || status >= 500 && status <= 599;
    }

    LiveHlsStartupFailure(String terminalDetail) {
        super(terminalDetail);
        this.status = 0;
        this.retryable = false;
    }

    boolean rejectRouteNow() {
        return status == 401 || status == 403 || status == 404 || status == 410 || status == 429;
    }
}
