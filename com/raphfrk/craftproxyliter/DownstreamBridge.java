/*******************************************************************************
 * Copyright (C) 2012 Raphfrk
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/
package com.raphfrk.craftproxyliter;

import java.io.EOFException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.raphfrk.protocol.EntityMap;
import com.raphfrk.protocol.KillableThread;
import com.raphfrk.protocol.Packet;
import com.raphfrk.protocol.Packet03Chat;
import com.raphfrk.protocol.Packet09Respawn;
import com.raphfrk.protocol.Packet1DDestroyEntity;
import com.raphfrk.protocol.Packet32PreChunk;
import com.raphfrk.protocol.Packet46Bed;
import com.raphfrk.protocol.ProtocolInputStream;
import com.raphfrk.protocol.ProtocolOutputStream;

public class DownstreamBridge extends KillableThread {

	final ProtocolInputStream in;
	final ProtocolOutputStream out;
	final PassthroughConnection ptc;
	final FairnessManager fm;
	
	DownstreamBridge(ProtocolInputStream in, ProtocolOutputStream out, PassthroughConnection ptc, FairnessManager fm) {

		this.in = in;
		this.out = out;
		this.ptc = ptc;
		this.fm = fm;
		this.setName("Downstream Bridge");

	}

	LinkedList<Byte> oldPacketIds = new LinkedList<Byte>();

	public void run() {

		CompressionManager cm = new CompressionManager(this, ptc, fm, out);

		Packet packet = new Packet();
		Packet packetBackup = packet;

		while(!killed()) {

			try {
				packet = in.getPacket(packet);
				if(packet == null) {
					ptc.printLogMessage("Timeout");
					kill();
					continue;
				}
			} catch (EOFException e) {
				ptc.printLogMessage("DownsteamBridge - EOF reached");
				kill();
				continue;
			} catch (IOException e) {
				System.out.println("IO ERROR");
				kill();
				continue;
			} catch (IllegalStateException ise) {
				kill();
				ptc.printLogMessage("Downstream link");
				ptc.printLogMessage(packetBackup + " Unable to read packet");
				ptc.printLogMessage("Packets: " + oldPacketIds);
				continue;
			}
			
			while(!ptc.messageQueue.isEmpty()) {
				String message = ptc.messageQueue.poll();
				if(message != null) {
					Packet03Chat chat = new Packet03Chat(message);
					try {
						fm.addPacketToHighQueue(out, chat, this);
					} catch (IOException e) {
						kill();
						continue;
					}
				}
			}

			if(packet.start < packet.end) {

				boolean dontSend = false;

				int packetId = packet.getByte(0) & 0xFF;
				
				if(packetId == 0x32) {
					int x = packet.getInt(1);
					int z = packet.getInt(5);
					boolean mode = packet.getByte(9) != 0;

					if(mode) {
						if(!ptc.connectionInfo.addChunk(x, z)) {
							ptc.printLogMessage("Chunk initialise packet sent for already initialised chunk " + x + ", " + z);
						}
					} else {
						if(!ptc.connectionInfo.removeChunk(x, z)) {
							ptc.printLogMessage("Chunk deallocate packet sent for unallocated chunk " + x + ", " + z);
						}
					}
				} else if(packetId == 0x33 || packetId == 0x51) {
					int x = (packet.getInt(1) >> 4);
					int z = (packet.getInt(5) >> 4);
					if(!ptc.connectionInfo.containsChunk(x, z)) {
						if (Globals.showUnallocatedChunkWarning()) {
							ptc.printLogMessage("Chunk update packet 0x" + Integer.toHexString(packetId) + " sent for unallocated chunk " + x + ", " + z + " adding fake init packet");
						}
						Packet fakeInit = new Packet32PreChunk(x, z, true);
						ptc.connectionInfo.addChunk(x, z);
						cm.addToQueue(fakeInit);
					}
				}

				// Map entity Ids
				int clientPlayerId = ptc.connectionInfo.clientPlayerId;
				int serverPlayerId = ptc.connectionInfo.serverPlayerId;
				Set<Integer> activeEntityIds = ptc.connectionInfo.activeEntities;


				int[] entityIdArray = EntityMap.entityIds[packetId];
				if(entityIdArray != null) {
					for(int pos : entityIdArray) {
						int id = packet.getInt(pos);
						if(id == clientPlayerId) {
							packet.setInt(pos, serverPlayerId);
							updateEntityId(ptc, activeEntityIds, packetId, serverPlayerId);
						} else if(id == serverPlayerId) {
							packet.setInt(pos, clientPlayerId);
							updateEntityId(ptc, activeEntityIds, packetId, clientPlayerId);
						} else {
							updateEntityId(ptc, activeEntityIds, packetId, id);
						}
					}
				}

				if(((packetId >= 0x32 && packetId < 0x36) || packetId == 0x82) || (packetId == 0x51)) {

					cm.addToQueue(packet);

					dontSend = true;
				}
				
				oldPacketIds.add(packet.buffer[packet.start & packet.mask]);
				if(this.oldPacketIds.size() > 20) {
					oldPacketIds.remove();
				}

				if(!dontSend) {
					ptc.connectionInfo.uploaded.addAndGet(packet.end - packet.start);

					if(!ptc.connectionInfo.forwardConnection && packetId == 0xFF) {
						String message = packet.getString16(1);
						String newHostname = redirectDetected(message, ptc);
						ptc.printLogMessage("Redirect detected: " + newHostname);
						ptc.connectionInfo.setHostname(newHostname);
						if(newHostname != null) {
							ptc.connectionInfo.redirect = true;
							
							Packet09Respawn normalPacket = new Packet09Respawn((int)0, (byte)2, (byte)0, (short)Globals.getMaxWorldHeight(), "DEFAULT");
							Packet09Respawn netherPacket = new Packet09Respawn((int)-1, (byte)2, (byte)0, (short)Globals.getMaxWorldHeight(), "DEFAULT");
							try {
								fm.addPacketToHighQueue(out, normalPacket, this);
								fm.addPacketToHighQueue(out, netherPacket, this);
							} catch (IOException ioe) {
								kill();
								continue;
							}
							
							Packet packetBed = new Packet46Bed(2, 0);
							try {
								fm.addPacketToHighQueue(out, packetBed, this);
							} catch (IOException ioe) {
								kill();
								continue;
							}
							cm.killTimerAndJoin();
							List<Integer> entityIds = ptc.connectionInfo.clearEntities();
							for(int id : entityIds) {
								if(id != clientPlayerId) {
									Packet destroy = new Packet1DDestroyEntity(id);
									try {
										fm.addPacketToHighQueue(out, destroy, this);
									} catch (IOException ioe) {
										kill();
										continue;
									}
								}
							}
							List<Long> activeChunks = ptc.connectionInfo.clearChunks();
							for(Long chunk : activeChunks) {
								int x = ConnectionInfo.getX(chunk);
								int z = ConnectionInfo.getZ(chunk);
								Packet unload = new Packet32PreChunk(x, z, false);
								try {
									fm.addPacketToHighQueue(out, unload, this);
								} catch (IOException ioe) {
									kill();
									continue;
								}
							}
							kill();
							continue;
						}

					} 

					try {
						fm.addPacketToHighQueue(out, packet, this);
					} catch (IOException ioe) {
						kill();
						continue;
					}
					
					
					
					/*try {
						out.sendPacket(packet);
					} catch (IOException e) {
						kill();
						continue;
					}*/
				}

			}

		}

		cm.killTimerAndJoin();

		synchronized(out) {
			try {
				out.flush();
			} catch (IOException e) {
				ptc.printLogMessage("Unable to flush output stream");
			}
		}
	}

	static void updateEntityId(PassthroughConnection ptc, Set<Integer> activeEntityIds, int packetId, int id) {
		if(packetId == 0x0d) {
			if(!activeEntityIds.remove(id)) {
				ptc.printLogMessage("Attempted to destroy non-existant entity " + id);
			}
		} else {
			if(activeEntityIds.add(id)) {
			}
		}
	}

	public static String redirectDetected(String reason, PassthroughConnection ptc) {

		String hostName = null;
		int portNum = -1;

		if(ptc != null && (!Globals.isQuiet())) {
			ptc.printLogMessage( "Kicked with: " + reason ); 
		}

		if( reason.indexOf("[Serverport]") != 0 && reason.indexOf("[Redirect]") != 0) {
			return null;
		}

		String[] split = reason.split( ":" );
		if( split.length == 3 ) {
			hostName = split[1].trim();
			try { 
				portNum = Integer.parseInt( split[2].trim() );
			} catch (Exception e) { portNum = -1; };
		} else  if( split.length == 2 ) {
			hostName = split[1].trim();
			try {
				portNum = 25565;
			} catch (Exception e) { portNum = -1; };
		}

		int commaPos = reason.indexOf(",");
		if(commaPos>=0) {
			return reason.substring(reason.indexOf(":") + 1).trim();
		}

		if( portNum != -1 ) {
			return hostName + ":" + portNum;
		} else {
			return null;

		}
	}

}
