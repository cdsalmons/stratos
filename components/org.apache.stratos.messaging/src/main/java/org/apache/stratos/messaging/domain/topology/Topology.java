/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.stratos.messaging.domain.topology;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.messaging.domain.topology.locking.TopologyLockHierarchy;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines a topology of serviceMap in Stratos.
 */
public class Topology implements Serializable {
    private static final long serialVersionUID = -2453583548027402122L;
    private final HashMap<String, Cluster> clusterMap;
    // Key: Service.serviceName
    private Map<String, Service> serviceMap;

    private boolean initialized;
    private static Log log = LogFactory.getLog(Topology.class);

    public Topology() {
        this.serviceMap = new HashMap<String, Service>();
        this.clusterMap = new HashMap<String, Cluster>();
    }

    public Collection<Service> getServices() {
        return serviceMap.values();
    }

    public void addService(Service service) {
        this.serviceMap.put(service.getServiceUuid(), service);
    }

    public void removeService(Service service) {
        this.serviceMap.remove(service.getServiceUuid());
        TopologyLockHierarchy.getInstance().removeTopologyLockForService(service.getServiceUuid());
    }

    public void removeService(String serviceUuid) {
        this.serviceMap.remove(serviceUuid);
        TopologyLockHierarchy.getInstance().removeTopologyLockForService(serviceUuid);
    }

    public Service getService(String serviceUuid) {
        return this.serviceMap.get(serviceUuid);
    }

    public boolean serviceExists(String serviceUuid) {
        return this.serviceMap.containsKey(serviceUuid);
    }

    public void addToCluterMap(Cluster cluster) {
        this.clusterMap.put(cluster.getClusterId(), cluster);
    }

    public void removeFromClusterMap(String cluserId) {
        clusterMap.remove(cluserId);
    }

    public Cluster getCluster(String clusterId) {
        return this.clusterMap.get(clusterId);
    }

    public boolean clusterExist(String clusterId) {
        return clusterMap.get(clusterId) != null;
    }

    public void clear() {
        this.serviceMap.clear();
        this.clusterMap.clear();
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public String toString() {
        return "Topology [serviceMap=" + serviceMap + ", initialized=" + initialized + "]";
    }
}
