/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package com.cloud.agent.api.routing;
import java.util.List;

import com.cloud.network.VpnUserVO;


public class VpnUsersCfgCommand extends RoutingCommand {
	public static class UsernamePassword{ 
		private String username;
		private String password;
		boolean add = true;
		
		public boolean isAdd() {
			return add;
		}
		public void setAdd(boolean add) {
			this.add = add;
		}
		public String getUsername() {
			return username;
		}
		public void setUsername(String username) {
			this.username = username;
		}
		public String getPassword() {
			return password;
		}
		public void setPassword(String password) {
			this.password = password;
		}
		public UsernamePassword(String username, String password) {
			super();
			this.username = username;
			this.password = password;
		}
		public UsernamePassword(String username, String password, boolean add) {
			super();
			this.username = username;
			this.password = password;
			this.add = add;
		}
		protected UsernamePassword() {
			//for Gson
		}
	}
	
	UsernamePassword [] userpwds;
	
    protected VpnUsersCfgCommand() {
    	
    }
    
    public VpnUsersCfgCommand(List<VpnUserVO> addUsers, List<VpnUserVO> removeUsers) {
    	userpwds = new UsernamePassword[addUsers.size() + removeUsers.size()];
    	int i = 0;
    	for (VpnUserVO vpnUser: removeUsers) {
    		userpwds[i++] = new UsernamePassword(vpnUser.getUserName(), vpnUser.getPassword(), false);
    	}
    	for (VpnUserVO vpnUser: addUsers) {
    		userpwds[i++] = new UsernamePassword(vpnUser.getUserName(), vpnUser.getPassword(), true);
    	}
    }

	@Override
	public boolean executeInSequence() {
		return true;
	}
    
}
