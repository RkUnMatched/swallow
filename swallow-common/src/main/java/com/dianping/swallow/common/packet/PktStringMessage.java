/**
 * Project: swallow-client
 * 
 * File Created at 2012-5-28
 * $Id$
 * 
 * Copyright 2010 dianping.com.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Dianping Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with dianping.com.
 */
package com.dianping.swallow.common.packet;

import com.dianping.swallow.common.util.Destination;

/**
 * TODO Comment of PktMessage
 * @author tong.song
 *
 */
public final class PktStringMessage extends Packet{
	private Destination dest;
	private String content;
	private int ackNum;

	public PktStringMessage(Destination dest, String content, int ackNum) {
		this.setPacketType(PacketType.STRING_MSG);
		this.dest = dest;
		this.setContent(content);
		this.ackNum = ackNum;
	}
	
	public void setContent(String content) {
		this.content = content;
	}
	
	public String getContent() {
		return content;
	}
	
	public int getAckNum(){
		return ackNum;
	}

	@Override
	public String toString() {
		// TODO Auto-generated method stub
		return content;
	}
	
}