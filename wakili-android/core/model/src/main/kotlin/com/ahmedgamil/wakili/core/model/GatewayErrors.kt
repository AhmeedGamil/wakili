package com.ahmedgamil.wakili.core.model

/** The gateway answered 401 — wrong or rotated token. */
class GatewayAuthException : Exception("unauthorized")

/** The gateway can't be reached (network down, wrong host, server stopped). */
class GatewayUnreachableException(cause: Throwable? = null) :
    Exception("gateway unreachable", cause)
