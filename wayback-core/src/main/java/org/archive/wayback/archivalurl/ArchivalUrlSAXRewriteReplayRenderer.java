/* ArchivalUrlSAXRewriteReplayRenderer
 *
 * $Id$
 *
 * Created on 12:15:33 PM Feb 12, 2009.
 *
 * Copyright (C) 2009 Internet Archive.
 *
 * This file is part of wayback.
 *
 * wayback is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * any later version.
 *
 * wayback is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with wayback; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.archive.wayback.archivalurl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.wayback.ReplayRenderer;
import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.WaybackException;
import org.archive.wayback.replay.HttpHeaderOperation;
import org.archive.wayback.replay.HttpHeaderProcessor;
import org.archive.wayback.replay.JSPExecutor;
import org.archive.wayback.replay.charset.CharsetDetector;
import org.archive.wayback.replay.charset.StandardCharsetDetector;
import org.archive.wayback.replay.html.ReplayParseEventDelegator;
import org.archive.wayback.replay.html.ReplayParseContext;
import org.archive.wayback.util.htmllex.ContextAwareLexer;
import org.htmlparser.Node;
import org.htmlparser.lexer.Lexer;
import org.htmlparser.lexer.Page;
import org.htmlparser.util.ParserException;

public class ArchivalUrlSAXRewriteReplayRenderer implements ReplayRenderer {
	private ReplayParseEventDelegator delegator = null;
	private HttpHeaderProcessor httpHeaderProcessor;
	private CharsetDetector charsetDetector = new StandardCharsetDetector();
	private final static String OUTPUT_CHARSET = "utf-8";

	public ArchivalUrlSAXRewriteReplayRenderer(HttpHeaderProcessor httpHeaderProcessor) {
		this.httpHeaderProcessor = httpHeaderProcessor;
	}

	// assume this is only called for appropriate doc types: html
	public void renderResource(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse, WaybackRequest wbRequest,
			CaptureSearchResult result, Resource resource,
			ResultURIConverter uriConverter, CaptureSearchResults results)
			throws ServletException, IOException, WaybackException {

		// copy the HTTP response code:
		HttpHeaderOperation.copyHTTPMessageHeader(resource, httpResponse);

		// transform the original headers according to our headerProcessor:
		Map<String,String> headers = HttpHeaderOperation.processHeaders(
				resource, result, uriConverter, httpHeaderProcessor);

		// prepare several objects for the parse:
		
		// a JSPExecutor:
		JSPExecutor jspExec = new JSPExecutor(uriConverter, httpRequest, 
				httpResponse, wbRequest, results, result, resource);
		
		// The URL of the page, for resolving in-page relative URLs: 
    	URL url = null;
		try {
			url = new URL(result.getOriginalUrl());
		} catch (MalformedURLException e1) {
			// TODO: this shouldn't happen...
			throw new IOException(e1);
		}

		// To make sure we get the length, we have to buffer it all up...
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		ArchivalUrlContextResultURIConverterFactory fact = 
			new ArchivalUrlContextResultURIConverterFactory(
					(ArchivalUrlResultURIConverter) uriConverter);
		// set up the context:
		ReplayParseContext context = 
			new ReplayParseContext(fact,url,result.getCaptureTimestamp());
		context.setOutputCharset(OUTPUT_CHARSET);
		context.setOutputStream(baos);
		context.setJspExec(jspExec);

		// determine the character set used to encode the document bytes:
		String charSet = charsetDetector.getCharset(resource, wbRequest);
		
		// and finally, parse, using the special lexer that knows how to
		// handle javascript blocks containing unescaped HTML entities:
		Page lexPage = new Page(resource,charSet);
    	ContextAwareLexer lex = new ContextAwareLexer(new Lexer(lexPage),
    			context);
    	Node node;
    	try {
			while((node = lex.nextNode()) != null) {
				delegator.handleNode(context, node);
			}
			delegator.handleParseComplete(context);
		} catch (ParserException e) {
			e.printStackTrace();
			throw new IOException(e);
		}

		// At this point, baos contains the utf-8 encoded bytes of our result:
		byte[] utf8Bytes = baos.toByteArray();
		// set the corrected length:
		headers.put(HttpHeaderOperation.HTTP_LENGTH_HEADER, 
				String.valueOf(utf8Bytes.length));
		headers.put("X-Wayback-Guessed-Charset", charSet);

		// send back the headers:
		HttpHeaderOperation.sendHeaders(headers, httpResponse);
		// Tomcat will always send a charset... It's trying to be smarter than
		// we are. If the original page didn't include a "charset" as part of
		// the "Content-Type" HTTP header, then Tomcat will use the default..
		// who knows what that is, or what that will do to the page..
		// let's try explicitly setting it to what we used:
		httpResponse.setCharacterEncoding(OUTPUT_CHARSET);

		httpResponse.getOutputStream().write(utf8Bytes);
	}

	/**
	 * @return the charsetDetector
	 */
	public CharsetDetector getCharsetDetector() {
		return charsetDetector;
	}

	/**
	 * @param charsetDetector the charsetDetector to set
	 */
	public void setCharsetDetector(CharsetDetector charsetDetector) {
		this.charsetDetector = charsetDetector;
	}

	/**
	 * @return the delegator
	 */
	public ReplayParseEventDelegator getDelegator() {
		return delegator;
	}

	/**
	 * @param delegator the delegator to set
	 */
	public void setDelegator(ReplayParseEventDelegator delegator) {
		this.delegator = delegator;
	}
}
