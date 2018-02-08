/*******************************************************************************
 * Copyright (c) 2016, 2017 Bosch Software Innovations GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Bosch Software Innovations GmbH - initial implementation. 
 *                                      add support for correlation context
 *    Achim Kraus (Bosch Software Innovations GmbH) - add principal and 
 *                                                    add TLS information to
 *                                                    correlation context
 *    Bosch Software Innovations GmbH - migrate to SLF4J
 ******************************************************************************/
package org.eclipse.californium.elements.tcp;

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;
import org.eclipse.californium.elements.EndpointContext;
import org.eclipse.californium.elements.TcpEndpointContext;
import org.eclipse.californium.elements.TlsEndpointContext;
import org.eclipse.californium.elements.util.StringUtil;
import org.eclipse.californium.scandium.auth.X509CertPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.cert.CertificateEncodingException;
import javax.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Utils for building for TCP/TLS endpoint context from channel.
 */
public class NettyContextUtils {

	private static final Logger LOGGER = LoggerFactory.getLogger(NettyContextUtils.class.getName());

	static java.security.cert.CertPath toJavaCertPath(javax.security.cert.X509Certificate[] javaxCertificatePath) throws Exception {
		List<java.security.cert.Certificate> javaCertificatePath = new LinkedList<>();
		for (javax.security.cert.X509Certificate javaxCertificate : javaxCertificatePath) {
			java.security.cert.Certificate javaCertificate = toJavaCertificate(javaxCertificate);
			javaCertificatePath.add(javaCertificate);
		}

		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		return cf.generateCertPath(javaCertificatePath);
	}

	static java.security.cert.Certificate toJavaCertificate(javax.security.cert.X509Certificate javaxCertificate) throws Exception {
		byte[] encoded = javaxCertificate.getEncoded();
		ByteArrayInputStream bis = new ByteArrayInputStream(encoded);
		java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
		return cf.generateCertificate(bis);
	}

	/**
	 * Build endpoint context related to the provided channel.
	 * 
	 * @param channel channel of endpoint context
	 * @return endpoint context
	 */
	public static EndpointContext buildEndpointContext(Channel channel) {
		InetSocketAddress address = (InetSocketAddress) channel.remoteAddress();
		String id = channel.id().asShortText();
		SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
		if (sslHandler != null) {
			SSLEngine sslEngine = sslHandler.engine();
			SSLSession sslSession = sslEngine.getSession();
			if (sslSession != null) {
				Principal principal = null;
				try {
					principal = sslSession.getPeerPrincipal();
					if (principal == null) {
						LOGGER.warn("Principal missing");
					} else {
						LOGGER.debug("Principal {}", principal.getName());
					}
				} catch (SSLPeerUnverifiedException e) {
					LOGGER.warn("Principal {}", e.getMessage());
					/* ignore it */
				}

				try {
					X509Certificate[] peerCertificateChain = sslSession.getPeerCertificateChain();
					if (peerCertificateChain != null && peerCertificateChain.length != 0) {
						java.security.cert.CertPath javaCertPath = toJavaCertPath(peerCertificateChain);
						principal = new X509CertPath(javaCertPath);
					}
				} catch (Exception e) {
					// TODO: do nothing
					e.printStackTrace();
				}

				byte[] sessionId = sslSession.getId();
				if (sessionId != null && sessionId.length > 0) {
					String sslId = StringUtil.byteArray2HexString(sessionId, 0);
					String cipherSuite = sslSession.getCipherSuite();
					LOGGER.debug("TLS({},{},{})",
							new Object[] { id, StringUtil.trunc(sslId, 14), cipherSuite });
					return new TlsEndpointContext(address, principal, id, sslId, cipherSuite);
				}
			}
			// TLS handshake not finished
			throw new IllegalStateException("TLS handshake " + id + " not ready!");
		}

		LOGGER.debug("TCP({})", id);
		return new TcpEndpointContext(address, id);
	}
}
