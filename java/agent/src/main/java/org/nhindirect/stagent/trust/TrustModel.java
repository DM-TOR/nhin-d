/* 
Copyright (c) 2010, NHIN Direct Project
All rights reserved.

Authors:
   Umesh Madan     umeshma@microsoft.com
   Greg Meyer      gm2552@cerner.com
 
Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer 
in the documentation and/or other materials provided with the distribution.  Neither the name of the The NHIN Direct Project (nhindirect.org). 
nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS 
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE 
GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF 
THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.nhindirect.stagent.trust;


import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import org.nhindirect.stagent.AgentError;
import org.nhindirect.stagent.AgentException;
import org.nhindirect.stagent.CryptoExtensions;
import org.nhindirect.stagent.IncomingMessage;
import org.nhindirect.stagent.MessageSignature;
import org.nhindirect.stagent.NHINDAddress;
import org.nhindirect.stagent.NHINDAddressCollection;
import org.nhindirect.stagent.OutgoingMessage;
import org.nhindirect.stagent.cert.SingnerCertPair;

/**
 * Default implementation of the trust model.
 * <p>
 * For outgoing messages each recipient is checked that it has a valid public certificate and that the certificate 
 * has a trusted anchor in the trust settings.
 * <p>
 * For incoming messages the sender's signature is validated and each recipient is checked to have a valid certificate.  The
 * sender is also validated to be trusted by the recipients.
 * @author Greg Meyer
 * @author Umesh Madan
 *
 */
public class TrustModel 
{
    public static final TrustModel Default = new TrustModel();
    
    private final TrustChainValidator certChainValidator;
    
    /**
     * Constructs a model with a default validator.
     */
    public TrustModel()
    {
    	certChainValidator = new TrustChainValidator();
    }

    /**
     * Constructs a model with a provided chain validator.
     * @param validator The trust validator used to valid trust of a certificate with trust anchors.
     */
    public TrustModel(TrustChainValidator validator)
    {
    	certChainValidator = validator;
    }    
    
    /**
     * Gets the chain validator associated with this model.
     * @return The chain validator associated with this model.
     */
    public TrustChainValidator getCertChainValidator()
    {
    	return certChainValidator;
    }
           
    /**
     * Enforces the trust policy an incoming message.  Each domain recipient's trust status is set according the models trust policy. 
     */
    public void enforce(IncomingMessage message)
    {
    	if (message == null)
    		throw new IllegalArgumentException();
    	
    	if (!message.hasSignatures())
    		throw new AgentException(AgentError.UntrustedMessage);
            	
    	findSenderSignatures(message);
        if (!message.hasSenderSignatures())
            throw new AgentException(AgentError.MissingSenderSignature);
                      
        // 
        // For each domain recipient, find at least one valid sender signature that the recipient trusts
        //        
        NHINDAddressCollection recipients = message.getDomainRecipients();
        for (NHINDAddress recipient : recipients)
        {
        	recipient.setStatus(TrustEnforcementStatus.Failed);
        	
        	// Find a trusted signature
        	MessageSignature trustedSignature = findTrustedSignature(message, recipient.getTrustAnchors());
        	
        	// verify the signature
        	if (trustedSignature != null)
        	{

                recipient.setStatus(trustedSignature.isThumbprintVerified() ? TrustEnforcementStatus.Success 
                		: TrustEnforcementStatus.Success_ThumbprintMismatch);
        	}
        }
    }
    
    /**
     * {@inheritDoc}}
     */    
    public void enforce(OutgoingMessage message)
    {
        if (message == null)
        {
            throw new IllegalArgumentException();
        }
        
        NHINDAddress sender = message.getSender();
        NHINDAddressCollection recipients = message.getRecipients();
        
        for (NHINDAddress recipient : recipients)
        {
            recipient.setStatus(TrustEnforcementStatus.Failed);                
            
            recipient.setCertificates(findTrustedCerts(recipient.getCertificates(), sender.getTrustAnchors()));
            if (recipient.hasCertificates())
            	recipient.setStatus(TrustEnforcementStatus.Success);

        }
    }
           
    private Collection<X509Certificate> findTrustedCerts(Collection<X509Certificate> certs, Collection<X509Certificate> anchors)
    {
        if (certs == null)
        {
            return null;
        }
        
        Collection<X509Certificate> trustedCerts = null;
        for (X509Certificate cert : certs)
        {
        	if (certChainValidator.isTrusted(cert, anchors))
        	{
                if (trustedCerts == null)
                {
                	trustedCerts = new ArrayList<X509Certificate>();
                }
                trustedCerts.add(cert);        		
        	}
        }
        
        return trustedCerts;
    }
    
    private void findSenderSignatures(IncomingMessage message)
    {
    	message.setSenderSignatures(null);
    	
    	NHINDAddress sender = message.getSender();
    	 
    	Collection<MessageSignature> senderSignatures = new ArrayList<MessageSignature>();
    	
    	// check for signatures at an individual level    	
    	Collection<SingnerCertPair> individualSenders = CryptoExtensions.findSignersByName(message.getSignature(), sender.getAddress(), null);
    	
    	// check for signatures at an org level
    	Collection<SingnerCertPair> orgSenders = CryptoExtensions.findSignersByName(message.getSignature(), 
    			sender.getHost(), Arrays.asList(new String[] {sender.getAddress()}));
    	
    	for (SingnerCertPair pair : individualSenders)
    		senderSignatures.add(new MessageSignature(pair.getSigner(), false, pair.getCertificate()));
    	
    	for (SingnerCertPair pair : orgSenders)
    		senderSignatures.add(new MessageSignature(pair.getSigner(), true, pair.getCertificate()));
    	
    	message.setSenderSignatures(senderSignatures);
    }
    
    private MessageSignature findTrustedSignature(IncomingMessage message, Collection<X509Certificate> anchors)    
    {
    	NHINDAddress sender = message.getSender();
    	
        Collection<MessageSignature> signatures = message.getSenderSignatures();
        MessageSignature lastTrustedSignature = null;    	
        
        for (MessageSignature signature : signatures)
        {
        	// The point of this loop is to find the most trusted signature
        	// to satisfy the most stringent enforcement policy.  Thumb print match policy is the best, so we will 
        	// return if we find a thumb print match... otherwise keep searching until we either find one
        	// of find the best possible match
        	
        	if (certChainValidator.isTrusted(signature.getSignerCert(), anchors) && signature.checkSignature())
        	{
        		if (!sender.hasCertificates())
        			return signature; // Can't really check thumbprints etc. So, this is about as good as its going to get
        		
            	if (signature.checkThumbprint(sender))
            	{
            		return signature;
            	}
            	
                //
                // We'll save this guy, but keep looking for a signer whose thumbprint we can verify
                // If we can't find one, we'll use the last trusted signer we found.. and just mark the recipient's trust
                // enforcement status as Success_ThumbprintMismatch
                //    
            	lastTrustedSignature = signature;        		
        	}
        	
        }
        
        return lastTrustedSignature;
    }
}
