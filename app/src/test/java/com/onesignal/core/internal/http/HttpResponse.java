/*
    This file is part of Doppel.

    Doppel is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Doppel is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2026 akano12
 */

package com.onesignal.core.internal.http;

// Stand-in for the response class of OneSignal 5, with the constructor of 5.11.0, whose Integer parameters LuaJ
// coerces like int
@SuppressWarnings("unused")
public final class HttpResponse {
    private final int statusCode;
    private final String payload;
    private final Throwable throwable;
    private final Integer retryAfterSeconds;
    private final Integer retryLimit;

    public HttpResponse(int statusCode, String payload, Throwable throwable, Integer retryAfterSeconds, Integer retryLimit) {
        this.statusCode = statusCode;
        this.payload = payload;
        this.throwable = throwable;
        this.retryAfterSeconds = retryAfterSeconds;
        this.retryLimit = retryLimit;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getPayload() {
        return payload;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Integer getRetryLimit() {
        return retryLimit;
    }
}
