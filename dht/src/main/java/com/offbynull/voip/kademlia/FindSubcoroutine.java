/*
 * Copyright (c) 2015, Kasra Faghihi, All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.offbynull.voip.kademlia;

import com.offbynull.coroutines.user.Continuation;
import com.offbynull.peernetic.core.actor.Context;
import com.offbynull.peernetic.core.actor.helpers.AddressTransformer;
import com.offbynull.peernetic.core.actor.helpers.IdGenerator;
import com.offbynull.peernetic.core.actor.helpers.RequestSubcoroutine;
import com.offbynull.peernetic.core.actor.helpers.Subcoroutine;
import com.offbynull.peernetic.core.actor.helpers.SubcoroutineRouter;
import com.offbynull.peernetic.core.actor.helpers.SubcoroutineRouter.AddBehaviour;
import com.offbynull.peernetic.core.actor.helpers.SubcoroutineRouter.Controller;
import com.offbynull.peernetic.core.actor.helpers.SubcoroutineRouter.ForwardResult;
import static com.offbynull.peernetic.core.gateways.log.LogMessage.info;
import com.offbynull.peernetic.core.shuttle.Address;
import static com.offbynull.voip.kademlia.AddressConstants.ROUTER_EXT_HANDLER_RELATIVE_ADDRESS;
import com.offbynull.voip.kademlia.externalmessages.FindRequest;
import com.offbynull.voip.kademlia.externalmessages.FindResponse;
import com.offbynull.voip.kademlia.model.Id;
import com.offbynull.voip.kademlia.model.IdXorMetricComparator;
import com.offbynull.voip.kademlia.model.Node;
import com.offbynull.voip.kademlia.model.NodeNotFoundException;
import com.offbynull.voip.kademlia.model.Router;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.lang3.Validate;

final class FindSubcoroutine implements Subcoroutine<List<Node>> {

    private final Address subAddress;
    private final Address timerAddress;
    private final Address logAddress;
    private final AddressTransformer addressTransformer;
    private final IdGenerator idGenerator;
    
    private final Router router;
    private final Id baseId;
    private final Id findId;
    private final int maxResults;
    private final int maxConcurrentRequests;
    
    private final boolean advertiseSelf;
    private final boolean ignoreSelf;
    
    public FindSubcoroutine(Address subAddress, State state, Id findId, int maxResults, boolean advertiseSelf, boolean ignoreSelf) {
        Validate.notNull(subAddress);
        Validate.notNull(state);
        Validate.notNull(findId);
        Validate.isTrue(maxResults >= 0); // why would anyone ever want 0 results? let thru anyways
        
        this.subAddress = subAddress;
        
        this.timerAddress = state.getTimerAddress();
        this.logAddress = state.getLogAddress();
        this.addressTransformer = state.getAddressTransformer();
        this.idGenerator = state.getIdGenerator();
        
        this.router = state.getRouter();
        this.baseId = state.getBaseId();
        this.findId = findId;
        this.maxResults = maxResults;
        this.maxConcurrentRequests = state.getMaxConcurrentRequestsPerFind();
        
        this.advertiseSelf = advertiseSelf;
        this.ignoreSelf = ignoreSelf;
    }
    
    @Override
    public Address getAddress() {
        return subAddress;
    }

    @Override
    public List<Node> run(Continuation cnt) throws Exception {
        Context ctx = (Context) cnt.getContext();

        ctx.addOutgoingMessage(subAddress, logAddress, info("Finding {}", findId));
        
        // Set up subcoroutine router
        Address routerAddress = subAddress.appendSuffix("finderreq" + idGenerator.generate());
        SubcoroutineRouter msgRouter = new SubcoroutineRouter(routerAddress, ctx);
        Controller msgRouterController = msgRouter.getController();
        
        // Get initial set of nodes to query from routing table
        List<Node> startNodes = router.find(findId, maxResults, false); // do not include stale nodes, we only want to contact alive nodes
        ctx.addOutgoingMessage(subAddress, logAddress, info("Route table entries closest to {}: {}", findId, startNodes));
        
        // Create sorted set of nodes to contact
        IdXorMetricComparator idClosenessComparator = new IdXorMetricComparator(findId);
        TreeSet<Node> contactSet = new TreeSet<>((x, y) -> idClosenessComparator.compare(x.getId(), y.getId()));
        contactSet.addAll(startNodes);

        // Create a sorted set of nodes to retain closest nodes in
        TreeSet<Node> closestSet = new TreeSet<>((x, y) -> idClosenessComparator.compare(x.getId(), y.getId()));
        
        // Execute requests
        Map<Subcoroutine<?>, Node> requestSubcoroutineToNodes = new HashMap<>(); // executing requests
        Set<Id> queriedSet = new HashSet<>(); // ids that have already been queried
        while (true) {
            // If there's room left to query more contacts that are closer to findId, do so... 
            while (msgRouterController.size() < maxConcurrentRequests && !contactSet.isEmpty()) {
                // Get next farthest away node to contact
                Node contactNode = contactSet.pollLast();
                
                // Add it to set of set of ids that have already been queried.. if it's already there, it means that it's already been
                // queried by this find, so skip it...
                boolean added = queriedSet.add(contactNode.getId());
                if (!added) {
                    continue;
                }
                                
                // Add it to the set of closest nodes (will be removed if node fails to respond)
                closestSet.add(contactNode);
                
                // If we already have maxResult closer nodes to findId, skip this node
                if (closestSet.size() > maxResults) {
                    Node removedNode = closestSet.pollLast();
                    if (removedNode == contactNode) {
                        continue;
                    }
                }
                
                // Initialize query
                Address destinationAddress = addressTransformer.toAddress(contactNode.getLink())
                        .appendSuffix(ROUTER_EXT_HANDLER_RELATIVE_ADDRESS);
                RequestSubcoroutine<FindResponse> reqSubcoroutine = new RequestSubcoroutine.Builder<FindResponse>()
                        .sourceAddress(routerAddress, idGenerator)
                        .destinationAddress(destinationAddress)
                        .timerAddress(timerAddress)
                        .request(new FindRequest(advertiseSelf ? baseId : null, findId, maxResults))
                        .addExpectedResponseType(FindResponse.class)
                        .attemptInterval(Duration.ofSeconds(2L))
                        .maxAttempts(5)
                        .throwExceptionIfNoResponse(false)
                        .build();
        
                ctx.addOutgoingMessage(subAddress, logAddress, info("Querying node {}", contactNode));
                
                // Add query to router
                msgRouterController.add(reqSubcoroutine, AddBehaviour.ADD_PRIME_NO_FINISH);
                requestSubcoroutineToNodes.put(reqSubcoroutine, contactNode);
            }
            
            
            // If there are no more requests running, it means we're finished
            if (msgRouterController.size() == 0) {
                ctx.addOutgoingMessage(subAddress, logAddress, info("Find complete: {}", closestSet));
                return new ArrayList<>(closestSet);
            }
            
            
            // Wait for next messange forward to the router
            cnt.suspend();
            ForwardResult fr = msgRouter.forward();
            
            // If a request completed from the forwarded message
            if (fr.isForwarded() && fr.isCompleted()) { // calling isCompleted by itself may throw an exception, check isForwarded first
                // Get response
                FindResponse findResponse = (FindResponse) fr.getResult();
                
                if (findResponse == null) {
                    // If failure, then mark as stale and remove from closest
                    // DONT BOTHER WITH TRYING TO CALCULATE LOCKING/UNLOCKING LOGIC. THE LOGIC WILL BECOME EXTREMELY CONVOLUTED. THE QUERY
                    // DID 5 REQUEST. IF NO ANSWER WAS GIVEN IN THE ALLOTED TIME, THEN MARK AS STALE!
                    Node contactedNode = requestSubcoroutineToNodes.remove(fr.getSubcoroutine());
                    try {
                        // not allowed to mark self as stale -- we may want to find self, but if we do and it's not responsive dont try to
                        // mark it as stale
                        if (!contactedNode.getId().equals(baseId)) {
                            router.stale(contactedNode);
                        }
                    } catch (NodeNotFoundException nnfe) { // may have been removed (already marked as stale) / may not be in routing tree
                        // Do nothing
                    }
                    closestSet.remove(contactedNode);
                } else {
                    // If success, then add returned nodes to contacts
                    Node[] nodes = findResponse.getNodes();
                    contactSet.addAll(Arrays.asList(nodes));
                    
                    // If we don't want to find our own ID / query ourselves... remove any reference to our own ID in the contactSet
                    // TODO: optimize this by removing before it's added to contactSet
                    if (ignoreSelf) {
                        contactSet.removeIf(x -> x.getId().equals(baseId));
                    }
                }
            }
        }
    }
    
}