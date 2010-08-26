/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.endpoint;

import org.mule.api.MuleException;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.endpoint.ImmutableEndpoint;
import org.mule.api.processor.MessageProcessor;
import org.mule.processor.builder.InterceptingChainMessageProcessorBuilder;

public class EndpointInterceptingChainMessageProcessorBuilder extends
    InterceptingChainMessageProcessorBuilder
{

    protected ImmutableEndpoint endpoint;

    public EndpointInterceptingChainMessageProcessorBuilder(ImmutableEndpoint endpoint,
                                                            FlowConstruct flowConstruct)
    {
        super(flowConstruct);
    }

    @Override
    protected MessageProcessor initializeMessageProcessor(Object processor) throws MuleException
    {
        if (processor instanceof EndpointAwareMessageProcessor)
        {
            ((EndpointAwareMessageProcessor) processor).injectEndpoint(endpoint);
        }
        return super.initializeMessageProcessor(processor);
    }

}
