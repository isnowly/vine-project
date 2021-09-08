/*
  Copyright 2021 The Cyber Project

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package io.github.aomsweet.cyber;

import io.netty.channel.*;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetSocketAddress;
import java.util.Queue;

/**
 * @author aomsweet
 */
public abstract class ClientRelayHandler<R> extends RelayHandler {

    protected Channel clientChannel;
    protected Credentials credentials;
    protected UpstreamProxy upstreamProxy;
    protected InetSocketAddress serverAddress;

    protected final ChannelManager channelManager;
    protected final UpstreamProxyManager upstreamProxyManager;

    public ClientRelayHandler(CyberServer cyber, InternalLogger logger) {
        super(cyber, logger);
        this.channelManager = cyber.channelManager;
        this.upstreamProxyManager = cyber.upstreamProxyManager;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        this.clientChannel = ctx.channel();
        super.channelRegistered(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (state == State.READY) {
            relay(msg);
        } else {
            channelRead0(msg);
        }
    }

    public abstract void channelRead0(Object msg) throws Exception;

    protected void doConnectServer(R request) throws Exception {
        ChannelFuture future = acquireChannelFuture(request);
        future.addListener(action -> {
            try {
                if (action.isSuccess()) {
                    if (clientChannel.isActive()) {
                        state = State.CONNECTED;
                        relayChannel = future.channel();
                        onConnected(request);
                    } else {
                        future.channel().close().addListener(ChannelFutureListener.CLOSE);
                    }
                } else {
                    logger.error("Unable to establish a remote connection.", action.cause());
                    this.onConnectFailed(request);
                }
            } catch (Exception e) {
                logger.error("{}: {}", e.getClass().getName(), e.getMessage(), e);
                close();
            }
        });
    }

    protected ChannelFuture acquireChannelFuture(R request) throws Exception {
        if (upstreamProxy == null) {
            if (upstreamProxyManager == null) {
                return channelManager.acquire(serverAddress, ctx);
            } else {
                Queue<? extends UpstreamProxy> upstreamProxies = upstreamProxyManager.lookupUpstreamProxies(request,
                    credentials, clientChannel.remoteAddress(), serverAddress);

                if (upstreamProxies == null || upstreamProxies.isEmpty()) {
                    return channelManager.acquire(serverAddress, ctx);
                } else {
                    CompleteChannelPromise promise = new CompleteChannelPromise(ctx.channel().eventLoop());
                    acquireChannelFuture(upstreamProxies, promise);
                    return promise;
                }
            }
        } else {
            return channelManager.acquire(serverAddress, upstreamProxy, ctx);
        }
    }

    protected void acquireChannelFuture(Queue<? extends UpstreamProxy> upstreamProxies, CompleteChannelPromise promise) {
        UpstreamProxy upstreamProxy = upstreamProxies.poll();
        if (logger.isDebugEnabled()) {
            logger.debug("Use upstream proxy: [{}]", upstreamProxy);
        }
        channelManager.acquire(serverAddress, upstreamProxy, ctx).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                this.upstreamProxy = upstreamProxy;
                promise.setChannel(future.channel()).setSuccess();
            } else {
                Throwable cause = future.cause();
                upstreamProxyManager.failConnectExceptionCaught(upstreamProxy, serverAddress, cause);
                if (upstreamProxies.peek() != null) {
                    acquireChannelFuture(upstreamProxies, promise);
                } else {
                    promise.setFailure(cause);
                }
            }
        });
    }

    public void doServerRelay() {
        if (relayChannel.isActive()) {
            relayChannel.pipeline().addLast(HandlerNames.RELAY, newServerRelayHandler());
            state = State.READY;
        } else {
            close();
        }
    }

    public ChannelHandler newServerRelayHandler() {
        return new ServerRelayHandler(cyber, clientChannel);
    }

    protected abstract void onConnected(R request) throws Exception;

    protected void onConnectFailed(R request) throws Exception {
        close();
    }

    @Override
    protected void releaseRelayChannel() {
        if (relayChannel == null || !relayChannel.isActive()) {
            return;
        }
        ChannelManager channelManager = cyber.channelManager;
        if (channelManager != null) {
            if (upstreamProxy == null) {
                channelManager.release(relayChannel, serverAddress);
            } else {
                channelManager.release(relayChannel, serverAddress, upstreamProxy);
            }
        }
    }

    public ClientRelayHandler<R> setCredentials(Credentials credentials) {
        this.credentials = credentials;
        return this;
    }

    public ClientRelayHandler<R> setServerAddress(InetSocketAddress serverAddress) {
        this.serverAddress = serverAddress;
        return this;
    }
}
