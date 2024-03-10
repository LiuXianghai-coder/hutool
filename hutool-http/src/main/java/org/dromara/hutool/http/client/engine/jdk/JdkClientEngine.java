/*
 * Copyright (c) 2023 looly(loolly@aliyun.com)
 * Hutool is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          https://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package org.dromara.hutool.http.client.engine.jdk;

import org.dromara.hutool.core.io.IORuntimeException;
import org.dromara.hutool.core.io.IoUtil;
import org.dromara.hutool.core.net.url.UrlBuilder;
import org.dromara.hutool.core.text.StrUtil;
import org.dromara.hutool.core.text.split.SplitUtil;
import org.dromara.hutool.core.util.ObjUtil;
import org.dromara.hutool.http.HttpException;
import org.dromara.hutool.http.HttpUtil;
import org.dromara.hutool.http.client.ClientConfig;
import org.dromara.hutool.http.client.engine.ClientEngine;
import org.dromara.hutool.http.client.Request;
import org.dromara.hutool.http.client.Response;
import org.dromara.hutool.http.client.body.HttpBody;
import org.dromara.hutool.http.client.cookie.GlobalCookieManager;
import org.dromara.hutool.http.meta.HeaderName;
import org.dromara.hutool.http.meta.HttpStatus;
import org.dromara.hutool.http.meta.Method;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;

/**
 * 基于JDK的UrlConnection的Http客户端引擎实现
 *
 * @author looly
 */
public class JdkClientEngine implements ClientEngine {

	private ClientConfig config;

	/**
	 * 构造
	 */
	public JdkClientEngine() {
	}

	@Override
	public JdkClientEngine init(final ClientConfig config) {
		this.config = config;
		return this;
	}

	@Override
	public Response send(final Request message) {
		return send(message, true);
	}

	/**
	 * 发送请求
	 *
	 * @param message 请求消息
	 * @param isAsync 是否异步，异步不会立即读取响应内容
	 * @return {@link Response}
	 */
	public JdkHttpResponse send(final Request message, final boolean isAsync) {
		final JdkHttpConnection conn = buildConn(message);
		try {
			doSend(conn, message);
		} catch (final IOException e) {
			// 出错后关闭连接
			IoUtil.closeQuietly(conn);
			throw new IORuntimeException(e);
		}

		return sendRedirectIfPossible(conn, message, isAsync);
	}

	@Override
	public Object getRawEngine() {
		return this;
	}

	@Override
	public void close() {
		// do nothing
	}

	/**
	 * 执行发送
	 *
	 * @param message 请求消息
	 * @throws IOException IO异常
	 */
	private void doSend(final JdkHttpConnection conn, final Request message) throws IOException {
		final HttpBody body = message.handledBody();
		if (null != body) {
			// 带有消息体，一律按照Rest方式发送
			body.writeClose(conn.getOutputStream());
			return;
		}

		// 非Rest简单GET请求
		conn.connect();
	}

	/**
	 * 构建{@link JdkHttpConnection}
	 *
	 * @param message {@link Request}消息
	 * @return {@link JdkHttpConnection}
	 */
	private JdkHttpConnection buildConn(final Request message) {
		final ClientConfig config = ObjUtil.defaultIfNull(this.config, ClientConfig::of);

		final JdkHttpConnection conn = JdkHttpConnection
			.of(message.handledUrl().toURL(), config.getProxy())
			.setConnectTimeout(config.getConnectionTimeout())
			.setReadTimeout(config.getReadTimeout())
			.setMethod(message.method())//
			.setSSLInfo(config.getSslInfo())
			// 关闭JDK自动转发，采用手动转发方式
			.setInstanceFollowRedirects(false)
			.setChunkedStreamingMode(message.isChunked() ? 4096 : -1)
			.setDisableCache(config.isDisableCache())
			// 覆盖默认Header
			.header(message.headers(), true);

		if (null == message.header(HeaderName.COOKIE)) {
			// 用户没有自定义Cookie，则读取全局Cookie信息并附带到请求中
			GlobalCookieManager.add(conn);
		}

		return conn;
	}

	/**
	 * 调用转发，如果需要转发返回转发结果，否则返回{@code null}
	 *
	 * @param conn    {@link JdkHttpConnection}}
	 * @param isAsync 最终请求是否异步
	 * @return {@link JdkHttpResponse}，无转发返回 {@code null}
	 */
	private JdkHttpResponse sendRedirectIfPossible(final JdkHttpConnection conn, final Request message, final boolean isAsync) {
		// 手动实现重定向
		if (message.maxRedirectCount() > 0) {
			final int code;
			try {
				code = conn.getCode();
			} catch (final IOException e) {
				// 错误时静默关闭连接
				conn.closeQuietly();
				throw new HttpException(e);
			}

			if (code != HttpURLConnection.HTTP_OK) {
				if (HttpStatus.isRedirected(code)) {
					message.url(getLocationUrl(message.handledUrl(), conn.header(HeaderName.LOCATION)));
					if (conn.redirectCount < message.maxRedirectCount()) {
						conn.redirectCount++;
						return send(message, isAsync);
					}
				}
			}
		}

		// 最终页面
		return new JdkHttpResponse(conn, true, message.charset(), isAsync,
			isIgnoreResponseBody(message.method()));
	}

	/**
	 * 获取转发的新的URL
	 *
	 * @param parentUrl 上级请求的URL
	 * @param location  获取的Location
	 * @return 新的URL
	 */
	private static UrlBuilder getLocationUrl(final UrlBuilder parentUrl, String location) {
		final UrlBuilder redirectUrl;
		if (!HttpUtil.isHttp(location) && !HttpUtil.isHttps(location)) {
			// issue#I5TPSY
			// location可能为相对路径
			if (!location.startsWith("/")) {
				location = StrUtil.addSuffixIfNot(parentUrl.getPathStr(), "/") + location;
			}

			// issue#3265, 相对路径中可能存在参数，单独处理参数
			final String query;
			final List<String> split = SplitUtil.split(location, "?", 2, true, true);
			if (split.size() == 2) {
				// 存在参数
				location = split.get(0);
				query = split.get(1);
			} else {
				query = null;
			}

			redirectUrl = UrlBuilder.of(parentUrl.getScheme(), parentUrl.getHost(), parentUrl.getPort(),
				location, query, null, parentUrl.getCharset());
		} else {
			redirectUrl = UrlBuilder.ofHttpWithoutEncode(location);
		}

		return redirectUrl;
	}

	/**
	 * 是否忽略读取响应body部分<br>
	 * HEAD、CONNECT、TRACE方法将不读取响应体
	 *
	 * @return 是否需要忽略响应body部分
	 */
	private boolean isIgnoreResponseBody(final Method method) {
		//https://developer.mozilla.org/zh-CN/docs/Web/HTTP/Methods/OPTIONS
		// OPTIONS请求可以带有响应体
		return Method.HEAD == method
			|| Method.CONNECT == method
			|| Method.TRACE == method;
	}
}
