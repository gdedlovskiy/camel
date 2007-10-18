/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.jms.requestor;

import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;

import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TemporaryQueue;

import org.apache.camel.impl.ServiceSupport;
import org.apache.camel.util.DefaultTimeoutMap;
import org.apache.camel.util.TimeoutMap;
import org.apache.camel.component.jms.JmsConfiguration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.SimpleMessageListenerContainer;
import org.springframework.jms.support.destination.DestinationResolver;

/**
 * @version $Revision: 1.1 $
 */
public class Requestor extends ServiceSupport implements MessageListener {
    private static final transient Log LOG = LogFactory.getLog(Requestor.class);
    private final JmsConfiguration configuration;
    private AbstractMessageListenerContainer listenerContainer;
    private TimeoutMap requestMap;
    private Destination replyTo;
                                
    public Requestor(JmsConfiguration configuration, ScheduledExecutorService executorService) {
        this.configuration = configuration;
        requestMap = new DefaultTimeoutMap(executorService, configuration.getRequestMapPurgePollTimeMillis());
    }

    public FutureTask getReceiveFuture(String correlationID, long requestTimeout) {
        FutureTask future = null;
/*
            // Deal with async handlers...

            Object currentHandler = requestMap.get(correlationID);
            if (currentHandler instanceof AsyncReplyHandler) {
                AsyncReplyHandler handler = (AsyncReplyHandler) currentHandler;
                future = handler.newResultHandler();
            }
*/

        if (future == null) {
            FutureHandler futureHandler = new FutureHandler();
            future = futureHandler;
            requestMap.put(correlationID, futureHandler, requestTimeout);
        }
        return future;
    }

    public void onMessage(Message message) {
        try {
            String correlationID = message.getJMSCorrelationID();
            if (correlationID == null) {
                LOG.warn("Ignoring message with no correlationID! " + message);
                return;
            }

            // lets notify the monitor for this response
            Object handler = requestMap.get(correlationID);
            if (handler == null) {
                LOG.warn("Response received for unknown correlationID: " + correlationID + " request: " + message);
            }
            else if (handler instanceof ReplyHandler) {
                ReplyHandler replyHandler = (ReplyHandler) handler;
                boolean complete = replyHandler.handle(message);
                if (complete) {
                    requestMap.remove(correlationID);
                }
            }
        }
        catch (JMSException e) {
            throw new FailedToProcessResponse(message, e);
        }
    }

    public AbstractMessageListenerContainer getListenerContainer() {
        if (listenerContainer == null) {
            listenerContainer = createListenerContainer();
        }
        return listenerContainer;
    }

    public void setListenerContainer(AbstractMessageListenerContainer listenerContainer) {
        this.listenerContainer = listenerContainer;
    }

    public Destination getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(Destination replyTo) {
        this.replyTo = replyTo;
    }

    // Implementation methods
    //-------------------------------------------------------------------------

    protected void doStart() throws Exception {
        AbstractMessageListenerContainer container = getListenerContainer();
        container.afterPropertiesSet();
    }

    protected void doStop() throws Exception {
        if (listenerContainer != null) {
            listenerContainer.stop();
            listenerContainer.destroy();
        }
    }

    protected AbstractMessageListenerContainer createListenerContainer() {
        SimpleMessageListenerContainer answer = new SimpleMessageListenerContainer();
        answer.setDestinationName("temporary");
        answer.setDestinationResolver(new DestinationResolver() {

            public Destination resolveDestinationName(Session session, String destinationName, boolean pubSubDomain) throws JMSException {
                TemporaryQueue queue = session.createTemporaryQueue();
                replyTo = queue;
                return queue;
            }
        });
        answer.setAutoStartup(true);
        answer.setMessageListener(this);
        answer.setPubSubDomain(false);
        answer.setSubscriptionDurable(false);
        answer.setConcurrentConsumers(1);
        answer.setConnectionFactory(configuration.getConnectionFactory());
        String clientId = configuration.getClientId();
        if (clientId != null) {
            clientId += ".Requestor";
            answer.setClientId(clientId);
        }
        TaskExecutor taskExecutor = configuration.getTaskExecutor();
        if (taskExecutor != null) {
            answer.setTaskExecutor(taskExecutor);
        }
        ExceptionListener exceptionListener = configuration.getExceptionListener();
        if (exceptionListener != null) {
            answer.setExceptionListener(exceptionListener);
        }
        return answer;
    }
}
