package ca.ubc.cs.cs317.dnslookup;

import java.io.*;
import java.net.*;
import java.util.*;

public class DNSLookupService {

    public static final int DEFAULT_DNS_PORT = 53;
    private static final int MAX_INDIRECTION_LEVEL_NS = 10;
    private static final int MAX_QUERY_ATTEMPTS = 3;
    private static final int MAX_DNS_MESSAGE_LENGTH = 512;
    private static final int MAX_EDNS_MESSAGE_LENGTH = 1280;
    private static final int SO_TIMEOUT = 5000;

    private final DNSCache cache = DNSCache.getInstance();
    private final Random random = new Random();
    private final DNSVerbosePrinter verbose;
    private final DatagramSocket socket;
	private DNSQuestion bestQuestion;

    /**
     * Creates a new lookup service. Also initializes the datagram socket object with a default timeout.
     *
     * @param verbose    A DNSVerbosePrinter listener object with methods to be called at key events in the query
     *                   processing.
     * @throws SocketException      If a DatagramSocket cannot be created.
     * @throws UnknownHostException If the nameserver is not a valid server.
     */
    public DNSLookupService(DNSVerbosePrinter verbose) throws SocketException, UnknownHostException {
        this.verbose = verbose;
        socket = new DatagramSocket();
        socket.setSoTimeout(SO_TIMEOUT);
    }

    /**
     * Closes the lookup service and related sockets and resources.
     */
    public void close() {
        socket.close();
    }

    /**
     * Examines a set of resource records to see if any of them are an answer to the given question.
     *
     * @param rrs       The set of resource records to be examined
     * @param question  The DNS question
     * @return          true if the collection of resource records contains an answer to the given question.
     */
    private boolean containsAnswer(Collection<CommonResourceRecord> rrs, DNSQuestion question) {
        for (CommonResourceRecord rr : rrs) {
            if (rr.getQuestion().equals(question) && rr.getRecordType() == question.getRecordType()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds all the results for a specific question. If there are valid (not expired) results in the cache, uses these
     * results, otherwise queries the nameserver for new records. If there are CNAME records associated to the question,
     * they are retrieved recursively for new records of the same type, and the returning set will contain both the
     * CNAME record and the resulting resource records of the indicated type.
     *
     * @param question             Host and record type to be used for search.
     * @param maxIndirectionLevels Number of CNAME indirection levels to support.
     * @return A set of resource records corresponding to the specific query requested.
     * @throws DNSErrorException If the number CNAME redirection levels exceeds the value set in
     *                           maxIndirectionLevels.
     */
    public Collection<CommonResourceRecord> getResultsFollowingCNames(DNSQuestion question, int maxIndirectionLevels)
            throws DNSErrorException {

        if (maxIndirectionLevels < 0) throw new DNSErrorException("CNAME indirection limit exceeded");

        Collection<CommonResourceRecord> directResults = iterativeQuery(question);
        if (containsAnswer(directResults, question)) {
            return directResults;
        }

        Set<CommonResourceRecord> newResults = new HashSet<>();
        for (CommonResourceRecord record : directResults) {
            newResults.add(record);
            if (record.getRecordType() == RecordType.CNAME) {
                newResults.addAll(getResultsFollowingCNames(
                        new DNSQuestion(record.getTextResult(), question.getRecordType(), question.getRecordClass()),
                        maxIndirectionLevels - 1));
            }
        }
        return newResults;
    }

    /**
     * Answers one question.  If there are valid (not expired) results in the cache, returns these results.
     * Otherwise it chooses the best nameserver to query, retrieves results from that server
     * (using individualQueryProcess which adds all the results to the cache) and repeats until either:
     *   the cache contains an answer to the query, or
     *   the cache contains an answer to the query that is a CNAME record rather than the requested type, or
     *   every "best" nameserver in the cache has already been tried.
     *
     *  @param question Host name and record type/class to be used for the query.
     */
    public Collection<CommonResourceRecord> iterativeQuery(DNSQuestion question)
            throws DNSErrorException {
        Set<CommonResourceRecord> ans = new HashSet<>();
        /* TODO: To be implemented by the student */

        InetAddress curAddress;
        List<CommonResourceRecord> cached = cache.getCachedResults(question);
        
        DNSQuestion origQuestion = new DNSQuestion(question.getHostName(), question.getRecordType(), question.getRecordClass());
        
        boolean didNameserver = false;
        
        while (cached.size() == 0){
        	


            List<CommonResourceRecord> bestNS = cache.getBestNameservers(question);
            List<CommonResourceRecord> bestServers = new ArrayList<CommonResourceRecord>();
            DNSQuestion bestQuestion = null;
            
            for (int i = 0; i < bestNS.size(); i++) {
            	CommonResourceRecord best = bestNS.get(i);
                bestQuestion = new DNSQuestion(best.getTextResult(),  RecordType.A, RecordClass.IN);
                bestServers = cache.getCachedResults(bestQuestion);
                
                if(bestServers.size() != 0) {
                	break;
                }
            }


            
            if(bestServers.size() == 0) {
            	didNameserver = true;
            	question = bestQuestion;
            	bestNS = cache.getBestNameservers(question);
            	CommonResourceRecord best = bestNS.get(0);
            	bestQuestion = new DNSQuestion(best.getTextResult(),  RecordType.A, RecordClass.IN);
                bestServers = cache.getCachedResults(bestQuestion);

            }
            
            curAddress = bestServers.get(0).getInetResult();
            
        	Set<ResourceRecord> newRecordds = individualQueryProcess(question, curAddress);

            
            cached = cache.getCachedResults(question);
        }
        
        if(didNameserver) {
        	curAddress = cached.get(0).getInetResult();
        	individualQueryProcess(origQuestion, curAddress);
            cached = cache.getCachedResults(origQuestion);

        }
        
        ans.addAll(cached);
        
//        individualQueryProcess(question);
        return ans;
    }
    
    private InetAddress getBestInet(DNSQuestion question) {

        CommonResourceRecord best = cache.getBestNameservers(question).get(0);

        DNSQuestion bestQuestion = new DNSQuestion(best.getTextResult(),  RecordType.A, RecordClass.IN);
        CommonResourceRecord bestServer = cache.getCachedResults(bestQuestion).get(0);

        return bestServer.getInetResult();
    }

    /**
     * Handles the process of sending an individual DNS query with a single question. Builds and sends the query (request)
     * message, then receives and parses the response. Received responses that do not match the requested transaction ID
     * are ignored. If no response is received after SO_TIMEOUT milliseconds, the request is sent again, with the same
     * transaction ID. The query should be sent at most MAX_QUERY_ATTEMPTS times, after which the function should return
     * without changing any values. If a response is received, all of its records are added to the cache.
     * <p>
     * If the reply contains a non-zero Rcode value, then throw a DNSErrorException.
     * <p>
     * The method verbose.printQueryToSend() must be called every time a new query message is about to be sent.
     *
     * @param question Host name and record type/class to be used for the query.
     * @param server   Address of the server to be used for the query.
     * @return If no response is received, returns null. Otherwise, returns a set of all resource records
     * received in the response.
     * @throws DNSErrorException if the Rcode in the response is non-zero
     */
    public Set<ResourceRecord> individualQueryProcess(DNSQuestion question, InetAddress server)
            throws DNSErrorException {
        /* TODO: To be implemented by the student */
    	Set<ResourceRecord> recordSet = new HashSet<ResourceRecord>();

    
		DNSMessage query = buildQuery(question);
		
    	byte[] request = query.getUsed();

        byte[] buffer = new byte[MAX_DNS_MESSAGE_LENGTH];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        int attempts = 0;
        boolean received = false;
	    while(!received && attempts < MAX_QUERY_ATTEMPTS){
	        try {
		
		        DatagramPacket packet = new DatagramPacket(request, request.length, server, DEFAULT_DNS_PORT);
		        socket.setSoTimeout(SO_TIMEOUT);
		        this.verbose.printQueryToSend("UDP", question, server, query.getID());

		        
				socket.send(packet);
		        socket.receive(response);
		        
		        DNSMessage responseMessage = new DNSMessage(buffer, response.getLength());
		        
		        if(responseMessage.getID() != query.getID() || !responseMessage.getQR()) {
		        	continue;
		        }
		        
		        recordSet = processResponse(responseMessage);
		        

		        if(recordSet == null) {
		        	recordSet = useTCP(query, server, question);
		        }
		        
		        received = true;
		        
	        
			} catch (SocketTimeoutException e) {
				attempts++;
			} catch (Exception e) {
			
				// TODO Auto-generated catch block
				e.printStackTrace();
				return null;
			}
	    }
	    
	    if (!received) {
	    	return null;
	    }
        for (ResourceRecord record: recordSet) {
        	if(record instanceof CommonResourceRecord) { 
        		cache.addResult((CommonResourceRecord) record);
        	}
        }

        

        return recordSet;
    }
    
    private Set<ResourceRecord> useTCP(DNSMessage query, InetAddress server, DNSQuestion question) {
    	byte[] request = query.getUsed();
    	int len = request.length;

        this.verbose.printQueryToSend("TCP", question, server, query.getID());


    	Set<ResourceRecord> recordSet = null;
    	
    	Socket tcpSocket;
		try {
			tcpSocket = new Socket(server, 53);

	    	OutputStream os = tcpSocket.getOutputStream();
	    	InputStream is = tcpSocket.getInputStream();
	    	os.write((len >> 8) & 0xFF);
	    	os.write(len & 0xFF);
	    	os.write(request);
	    	int lenHi = is.read();
	    	int lenLo = is.read();
	    	int responseLen = (lenHi << 8) | lenLo;

	    	byte[] buffer = new byte[responseLen];
	    	is.read(buffer);
	    	
	        DNSMessage responseMessage = new DNSMessage(buffer, responseLen);

	        
	        recordSet = processResponse(responseMessage);
	        tcpSocket.close();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (DNSErrorException e) {
			
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	
    	return recordSet;
    }
    


    /**
     * Creates a DNSMessage containing a DNS query.
     * A random transaction ID must be generated and filled in the corresponding part of the query. The query
     * must be built as an iterative (non-recursive) request for a regular query with a single question. When the
     * function returns, the message's buffer's position (`message.buffer.position`) must be equivalent
     * to the size of the query data.
     *
     * @param question    Host name and record type/class to be used for the query.
     * @return The DNSMessage containing the query.
     */
    public DNSMessage buildQuery(DNSQuestion question) {
        /* TODO: To be implemented by the student */
    	
    	
    	// get random id
    	Random r = new Random();
        int id = r.nextInt(0xffff + 1);
        
    	DNSMessage message = new DNSMessage((short)id);
    	message.addQuestion(question);
    	
    	
    	message.setQDCount(1);
    	
    	// We don't need to do anything for anything else, as all 0's is what we want.

    	

        return message;
    }

    /**
     * Parses and processes a response received by a nameserver.
     * If the reply contains a non-zero Rcode value, then throw a DNSErrorException.
     * Adds all resource records found in the response message to the cache.
     * Calls methods in the verbose object at appropriate points of the processing sequence. Must be able
     * to properly parse records of the types: A, AAAA, NS, CNAME and MX (the priority field for MX may be ignored). Any
     * other unsupported record type must create a record object with the data represented as a hex string (see method
     * byteArrayToHexString).
     *
     * @param message The DNSMessage received from the server.
     * @return A set of all resource records received in the response.
     * @throws DNSErrorException if the Rcode value in the reply header is non-zero
     */
    public Set<ResourceRecord> processResponse(DNSMessage message) throws DNSErrorException {
        /* TODO: To be implemented by the student */
    	
    	
    	if(message.getRcode() != 0) {
    		throw new DNSErrorException("Rcode was not 0");
    	}
    	
    	this.verbose.printResponseHeaderInfo(message.getID(), message.getAA(), message.getTC(), message.getRcode());
    	
    	if(message.getTC()) {
    		return null;
    	}
    	
    
    		
    	Set<ResourceRecord> recordSet = new HashSet<ResourceRecord>();
    	
    	int numQuestions = message.getQDCount();
    	DNSQuestion question = null;
    	
    	for (int i = 0; i < numQuestions; i++) {
    		question = message.getQuestion();
    	}
    	
    	int anCount = message.getANCount();
    	
        this.verbose.printAnswersHeader(anCount);
        
        for (int i = 0; i < anCount; i++) {
        	ResourceRecord rRecord = message.getRR();
    		recordSet.add(rRecord);
    		this.verbose.printIndividualResourceRecord(rRecord, rRecord.getRecordType().getCode(), rRecord.getQuestion().getRecordClass().getCode());
        }

        int nsCount = message.getNSCount();
        this.verbose.printNameserversHeader(nsCount);

        for (int i = 0; i < nsCount; i++) {
        	ResourceRecord rRecord = message.getRR();
    		recordSet.add(rRecord);
    		this.verbose.printIndividualResourceRecord(rRecord, rRecord.getRecordType().getCode(), rRecord.getQuestion().getRecordClass().getCode());
        }
        
        int arCount = message.getARCount();

        this.verbose.printAdditionalInfoHeader(arCount);
        for (int i = 0; i < arCount; i++) {
        	ResourceRecord rRecord = message.getRR();
    		recordSet.add(rRecord);
    		this.verbose.printIndividualResourceRecord(rRecord, rRecord.getRecordType().getCode(), rRecord.getQuestion().getRecordClass().getCode());
        }

        for (ResourceRecord record: recordSet) {
        	if(record instanceof CommonResourceRecord) { 
        		cache.addResult((CommonResourceRecord) record);
        	}
        }
    	
        return recordSet;
    }

    public static class DNSErrorException extends Exception {
        public DNSErrorException(String msg) {
            super(msg);
        }
    }
}