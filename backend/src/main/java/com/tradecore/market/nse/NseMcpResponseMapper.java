package com.tradecore.market.nse;

import com.tradecore.market.MarketDataConnectivity.EndpointStatus;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

final class NseMcpResponseMapper {

    private NseMcpResponseMapper() {
    }

    static EndpointStatus map(InitializeResult initialization, ListToolsResult toolListing) {
        return new EndpointStatus(
                true,
                initialization.protocolVersion(),
                toolListing.tools().size(),
                null);
    }

    static EndpointStatus failure(String category) {
        return new EndpointStatus(false, null, 0, category);
    }
}
