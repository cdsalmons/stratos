package org.apache.stratos.python.cartridge.agent.test;/*
 * Licensed to the Apache Software Foundation (ASF) under one 
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY 
 * KIND, either express or implied.  See the License for the 
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.activemq.broker.BrokerService;
import org.apache.commons.exec.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.common.threading.StratosThreadPool;
import org.apache.stratos.messaging.broker.publish.EventPublisher;
import org.apache.stratos.messaging.broker.publish.EventPublisherPool;
import org.apache.stratos.messaging.event.Event;
import org.apache.stratos.messaging.listener.instance.status.InstanceActivatedEventListener;
import org.apache.stratos.messaging.listener.instance.status.InstanceStartedEventListener;
import org.apache.stratos.messaging.message.receiver.instance.status.InstanceStatusEventReceiver;
import org.apache.stratos.messaging.message.receiver.topology.TopologyEventReceiver;
import org.apache.stratos.messaging.util.MessagingUtil;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PythonAgentTestManager {
    protected final Properties integrationProperties = new Properties();
    public static final String PATH_SEP = File.separator;
    private static final Log log = LogFactory.getLog(PythonAgentTestManager.class);
    protected BrokerService broker = new BrokerService();

    public final long TIMEOUT = 180000;
    public static final String NEW_LINE = System.getProperty("line.separator");
    public static final String ACTIVEMQ_AMQP_BIND_ADDRESS = "activemq.amqp.bind.address";
    public static final String ACTIVEMQ_MQTT_BIND_ADDRESS = "activemq.mqtt.bind.address";
    public static final String CEP_PORT = "cep.port";
    public static final String CEP_SSL_PORT = "cep.ssl.port";
    public static final String DISTRIBUTION_NAME = "distribution.name";
    protected final UUID PYTHON_AGENT_DIR_NAME = UUID.randomUUID();

    protected Map<Integer, ServerSocket> serverSocketMap = new HashMap<>();
    protected Map<String, Executor> executorList = new HashMap<String, Executor>();

    protected int cepPort;
    protected int cepSSLPort;
    protected String amqpBindAddress;
    protected String mqttBindAddress;
    protected String distributionName;

    protected boolean eventReceiverInitiated = false;
    protected TopologyEventReceiver topologyEventReceiver;
    protected InstanceStatusEventReceiver instanceStatusEventReceiver;
    protected boolean instanceStarted;
    protected boolean instanceActivated;
    protected ByteArrayOutputStreamLocal outputStream;
    private ThriftTestServer thriftTestServer;

    /**
     * Setup method for test method testPythonCartridgeAgent
     */
    protected void setup(String resourcePath) {
        try {
            startBroker();
        }
        catch (Exception e) {
            log.error("Error while starting MB", e);
            return;
        }
        if (!this.eventReceiverInitiated) {
            ExecutorService executorService = StratosThreadPool.getExecutorService("TEST_THREAD_POOL", 15);
            topologyEventReceiver = new TopologyEventReceiver();
            topologyEventReceiver.setExecutorService(executorService);
            topologyEventReceiver.execute();

            instanceStatusEventReceiver = new InstanceStatusEventReceiver();
            instanceStatusEventReceiver.setExecutorService(executorService);
            instanceStatusEventReceiver.execute();

            this.instanceStarted = false;
            instanceStatusEventReceiver.addEventListener(new InstanceStartedEventListener() {
                @Override
                protected void onEvent(Event event) {
                    log.info("Instance started event received");
                    instanceStarted = true;
                }
            });

            this.instanceActivated = false;
            instanceStatusEventReceiver.addEventListener(new InstanceActivatedEventListener() {
                @Override
                protected void onEvent(Event event) {
                    log.info("Instance activated event received");
                    instanceActivated = true;
                }
            });

            this.eventReceiverInitiated = true;
        }

        // Start Thrift server to emulate CEP
        thriftTestServer = new ThriftTestServer();
        try {
            File file = new File(getResourcesPath("common") + PATH_SEP + "stratos-health-stream-def.json");
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            String str = new String(data, "UTF-8");
            if (str.equals("")) {
                log.warn("Stream definition of health stat stream is empty. Thrift server will not function properly");
            }
            thriftTestServer.addStreamDefinition(str, -1234);
            // start with non-ssl port; test server will automatically bind to ssl port
            thriftTestServer.start(cepPort);
            log.info("Started Thrift server with stream definition: " + str);
        }
        catch (Exception e) {
            log.error("Could not start Thrift test server", e);
        }


        String agentPath = setupPythonAgent(resourcePath);
        log.info("Python agent working directory name: " + PYTHON_AGENT_DIR_NAME);
        log.info("Starting python cartridge agent...");
        this.outputStream = executeCommand("python " + agentPath + PATH_SEP + "agent.py");
    }


    protected void tearDown() {
        tearDown(null);
    }

    /**
     * TearDown method for test method testPythonCartridgeAgent
     */
    protected void tearDown(String sourcePath) {
        for (Map.Entry<String, Executor> entry : executorList.entrySet()) {
            try {
                String commandText = entry.getKey();
                Executor executor = entry.getValue();
                log.info("Terminating process: " + commandText);
                executor.setExitValue(0);
                executor.getWatchdog().destroyProcess();
            }
            catch (Exception ignore) {
            }
        }
        // wait until everything cleans up to avoid connection errors
        sleep(1000);
        for (ServerSocket serverSocket : serverSocketMap.values()) {
            try {
                log.info("Stopping socket server: " + serverSocket.getLocalSocketAddress());
                serverSocket.close();
            }
            catch (IOException ignore) {
            }
        }
        try {
            if (thriftTestServer != null) {
                thriftTestServer.stop();
            }
        }
        catch (Exception e) {
            log.error("Could not stop Thrift test server", e);
        }

        try {
            log.info("Deleting source checkout folder...");
            FileUtils.deleteDirectory(new File(sourcePath));
        }
        catch (Exception ignore) {
        }
        this.instanceStatusEventReceiver.terminate();
        this.topologyEventReceiver.terminate();

        this.instanceActivated = false;
        this.instanceStarted = false;
        try {
            broker.stop();
        }
        catch (Exception e) {
            log.error("Error while stopping the broker service", e);
        }
    }

    public PythonAgentTestManager() {
        try {
            integrationProperties
                    .load(PythonAgentTestManager.class.getResourceAsStream(PATH_SEP + "integration-test.properties"));
            distributionName = integrationProperties.getProperty(DISTRIBUTION_NAME);
            amqpBindAddress = integrationProperties.getProperty(ACTIVEMQ_AMQP_BIND_ADDRESS);
            mqttBindAddress = integrationProperties.getProperty(ACTIVEMQ_MQTT_BIND_ADDRESS);
            cepPort = Integer.parseInt(integrationProperties.getProperty(CEP_PORT));
            cepSSLPort = Integer.parseInt(integrationProperties.getProperty(CEP_SSL_PORT));
            log.info("PCA integration properties: " + integrationProperties.toString());
        }
        catch (IOException e) {
            log.error("Error loading integration-test.properties file from classpath. Please make sure that file " +
                    "exists in classpath.", e);
        }
    }

    protected void startBroker() throws Exception {
        broker.addConnector(amqpBindAddress);
        broker.addConnector(mqttBindAddress);
        broker.setBrokerName("testBroker");
        broker.setDataDirectory(
                PythonAgentTestManager.class.getResource(PATH_SEP).getPath() + PATH_SEP + ".." + PATH_SEP +
                        PYTHON_AGENT_DIR_NAME + PATH_SEP + "activemq-data");
        broker.start();
        log.info("Broker service started!");
    }

    protected void startCommunicatorThread() {
        Thread communicatorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> outputLines = new ArrayList<String>();
                while (!outputStream.isClosed()) {
                    List<String> newLines = getNewLines(outputLines, outputStream.toString());
                    if (newLines.size() > 0) {
                        for (String line : newLines) {
                            if (line.contains("Exception in thread") || line.contains("ERROR")) {
                                try {
                                    throw new RuntimeException(line);
                                }
                                catch (Exception e) {
                                    log.error("ERROR found in PCA log", e);
                                }
                            }
                            log.info("[PCA] " + line);
                        }
                    }
                    sleep(100);
                }
            }
        });
        communicatorThread.start();
    }

    /**
     * Start server socket
     *
     * @param port
     */
    protected void startServerSocket(final int port) {
        Thread socketThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) { // do this infinitely until test is complete
                    try {
                        ServerSocket serverSocket = new ServerSocket(port);
                        serverSocketMap.put(port, serverSocket);
                        log.info("Server socket started on port: " + port);
                        Socket socket = serverSocket.accept();
                        log.info("Client connected to [port] " + port);

                        InputStream is = socket.getInputStream();
                        byte[] buffer = new byte[1024];
                        int read;
                        while (true) {
                            if (socket.isClosed()) {
                                log.info("Socket for [port] " + port + " has been closed.");
                                break;
                            }
                            if ((read = is.read(buffer)) != -1) {
                                String output = new String(buffer, 0, read);
                                log.info("Message received for [port] " + port + ", [message] " + output);
                            }
                        }
                    }
                    catch (IOException e) {
                        String message = "Could not start server socket: [port] " + port;
                        log.error(message, e);
                        throw new RuntimeException(message, e);
                    }
                }
            }
        });
        socketThread.start();
    }


    protected static String getResourcesPath() {
        return PythonAgentTestManager.class.getResource(PATH_SEP).getPath() + PATH_SEP + ".." + PATH_SEP +
                ".." + PATH_SEP + "src" + PATH_SEP + "test" + PATH_SEP + "resources";
    }

    protected static String getResourcesPath(String resourcesPath) {
        return PythonAgentTestManager.class.getResource(PATH_SEP).getPath() + ".." + PATH_SEP + ".." +
                PATH_SEP + "src" + PATH_SEP + "test" + PATH_SEP + "resources" + PATH_SEP + resourcesPath;
    }

    /**
     * Copy python agent distribution to a new folder, extract it and copy sample configuration files
     *
     * @return
     */
    protected String setupPythonAgent(String resourcesPath) {
        try {
            log.info("Setting up python cartridge agent...");


            String srcAgentPath = PythonAgentTestManager.class.getResource(PATH_SEP).getPath() +
                    PATH_SEP + ".." + PATH_SEP + ".." + PATH_SEP + ".." + PATH_SEP + "distribution" + PATH_SEP +
                    "target" + PATH_SEP + distributionName + ".zip";
            String unzipDestPath =
                    PythonAgentTestManager.class.getResource(PATH_SEP).getPath() + PATH_SEP + ".." + PATH_SEP +
                            PYTHON_AGENT_DIR_NAME + PATH_SEP;
            //FileUtils.copyFile(new File(srcAgentPath), new File(destAgentPath));
            unzip(srcAgentPath, unzipDestPath);
            String destAgentPath = PythonAgentTestManager.class.getResource(PATH_SEP).getPath() + PATH_SEP + ".." +
                    PATH_SEP + PYTHON_AGENT_DIR_NAME + PATH_SEP + distributionName;

            String srcAgentConfPath = getResourcesPath(resourcesPath) + PATH_SEP + "agent.conf";
            String destAgentConfPath = destAgentPath + PATH_SEP + "agent.conf";
            FileUtils.copyFile(new File(srcAgentConfPath), new File(destAgentConfPath));

            String srcLoggingIniPath = getResourcesPath(resourcesPath) + PATH_SEP + "logging.ini";
            String destLoggingIniPath = destAgentPath + PATH_SEP + "logging.ini";
            FileUtils.copyFile(new File(srcLoggingIniPath), new File(destLoggingIniPath));

            String srcPayloadPath = getResourcesPath(resourcesPath) + PATH_SEP + "payload";
            String destPayloadPath = destAgentPath + PATH_SEP + "payload";
            FileUtils.copyDirectory(new File(srcPayloadPath), new File(destPayloadPath));

            log.info("Changing extension scripts permissions");
            File extensionsPath = new File(destAgentPath + PATH_SEP + "extensions" + PATH_SEP + "bash");
            File[] extensions = extensionsPath.listFiles();
            for (File extension : extensions) {
                extension.setExecutable(true);
            }

            log.info("Python cartridge agent setup completed");

            return destAgentPath;
        }
        catch (Exception e) {
            String message = "Could not copy cartridge agent distribution";
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
    }

    private void unzip(String zipFilePath, String destDirectory) throws IOException {
        File destDir = new File(destDirectory);
        if (!destDir.exists()) {
            destDir.mkdir();
        }
        ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath));
        ZipEntry entry = zipIn.getNextEntry();
        // iterates over entries in the zip file
        while (entry != null) {
            String filePath = destDirectory + File.separator + entry.getName();
            if (!entry.isDirectory()) {
                // if the entry is a file, extracts it
                extractFile(zipIn, filePath);
            } else {
                // if the entry is a directory, make the directory
                File dir = new File(filePath);
                dir.mkdir();
            }
            zipIn.closeEntry();
            entry = zipIn.getNextEntry();
        }
        zipIn.close();
    }

    private void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
        byte[] bytesIn = new byte[4096];
        int read = 0;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
    }

    /**
     * Execute shell command
     *
     * @param commandText
     */
    protected ByteArrayOutputStreamLocal executeCommand(final String commandText) {
        final ByteArrayOutputStreamLocal outputStream = new ByteArrayOutputStreamLocal();
        try {
            CommandLine commandline = CommandLine.parse(commandText);
            DefaultExecutor exec = new DefaultExecutor();
            PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
            exec.setWorkingDirectory(new File(
                    PythonAgentTestManager.class.getResource(PATH_SEP).getPath() + PATH_SEP + ".." + PATH_SEP +
                            PYTHON_AGENT_DIR_NAME));
            exec.setStreamHandler(streamHandler);
            ExecuteWatchdog watchdog = new ExecuteWatchdog(TIMEOUT);
            exec.setWatchdog(watchdog);
            exec.execute(commandline, new ExecuteResultHandler() {
                @Override
                public void onProcessComplete(int i) {
                    log.info(commandText + " process completed");
                }

                @Override
                public void onProcessFailed(ExecuteException e) {
                    log.error(commandText + " process failed", e);
                }
            });
            executorList.put(commandText, exec);
            return outputStream;
        }
        catch (Exception e) {
            log.error(outputStream.toString(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Sleep current thread
     *
     * @param time
     */
    protected void sleep(long time) {
        try {
            Thread.sleep(time);
        }
        catch (InterruptedException ignore) {
        }
    }

    /**
     * Return new lines found in the output
     *
     * @param currentOutputLines current output lines
     * @param output             output
     * @return
     */
    protected List<String> getNewLines(List<String> currentOutputLines, String output) {
        List<String> newLines = new ArrayList<String>();

        if (StringUtils.isNotBlank(output)) {
            String[] lines = output.split(NEW_LINE);
            for (String line : lines) {
                if (!currentOutputLines.contains(line)) {
                    currentOutputLines.add(line);
                    newLines.add(line);
                }
            }
        }
        return newLines;
    }

    /**
     * Publish messaging event
     *
     * @param event
     */
    protected void publishEvent(Event event) {
        String topicName = MessagingUtil.getMessageTopicName(event);
        EventPublisher eventPublisher = EventPublisherPool.getPublisher(topicName);
        eventPublisher.publish(event);
    }


    /**
     * Implements ByteArrayOutputStream.isClosed() method
     */
    protected class ByteArrayOutputStreamLocal extends org.apache.commons.io.output.ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            super.close();
            closed = true;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}