/*
 *  eXist Mail Module Extension SendEmailFunction
 *  Copyright (C) 2006-09 Adam Retter <adam@exist-db.org>
 *  www.exist-db.org
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software Foundation
 *  Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *  
 *  $Id$
 */

package org.exist.xquery.modules.mail;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;

import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;

import org.apache.log4j.Logger;
import org.exist.dom.QName;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.system.GetVersion;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

//send-email specific imports
import org.exist.util.Base64Encoder;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * eXist Mail Module Extension SendEmailFunction
 * 
 * The email sending functionality of the eXist Mail Module Extension that
 * allows email to be sent from XQuery using either SMTP or Sendmail.  
 * 
 * @author Adam Retter <adam@exist-db.org>
 * @author Robert Walpole <robert.walpole@devon.gov.uk>
 * @author Andrzej Taramina <andrzej@chaeron.com>
 * @serial 2009-11-04
 * @version 1.4
 *
 * @see org.exist.xquery.BasicFunction#BasicFunction(org.exist.xquery.XQueryContext, org.exist.xquery.FunctionSignature)
 */
public class SendEmailFunction extends BasicFunction
{
    protected static final Logger logger = Logger.getLogger(SendEmailFunction.class);
	
    private String charset;
	
    public final static FunctionSignature deprecated = new FunctionSignature(
        new QName("send-email", MailModule.NAMESPACE_URI, MailModule.PREFIX),
        "Sends an email through the SMTP Server.",
        new SequenceType[]
        {
            new FunctionParameterSequenceType("email", Type.ELEMENT, Cardinality.ONE_OR_MORE, "The email message in the following format: <mail> <from/> <reply-to/> <to/> <cc/> <bcc/> <subject/> <message> <text/> <xhtml/> </message> <attachment filename=\"\" mimetype=\"\">xs:base64Binary</attachment> </mail>."),
            new FunctionParameterSequenceType("server", Type.STRING, Cardinality.ZERO_OR_ONE, "The SMTP server.  If empty, then it tries to use the local sendmail program."),
            new FunctionParameterSequenceType("charset", Type.STRING, Cardinality.ZERO_OR_ONE, "The charset value used in the \"Content-Type\" message header (Defaults to UTF-8)")
        },
        new FunctionReturnSequenceType(Type.BOOLEAN, Cardinality.ONE_OR_MORE, "true if the email message was successfully sent")
    );

    /**
     * SendEmail Constructor
     *
     * @param context	The Context of the calling XQuery
     */
    public SendEmailFunction(XQueryContext context)
    {
            super( context, deprecated );
    }

    /**
     * evaluate the call to the xquery send-email function,
     * it is really the main entry point of this class
     *
     * @param args		arguments from the send-email() function call
     * @param contextSequence	the Context Sequence to operate on (not used here internally!)
     * @return		A sequence representing the result of the send-email() function call
     *
     * @see org.exist.xquery.BasicFunction#eval(org.exist.xquery.value.Sequence[], org.exist.xquery.value.Sequence)
     */
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException
    {
        try
        {
            //get the charset parameter, default to UTF-8
            if (!args[2].isEmpty())
            {
                charset =  args[2].getStringValue();
            }
            else
            {
                charset =  "UTF-8";
            }

            //Parse the XML <mail> into a mail Object
            List<Element> mailElements = new ArrayList<Element>();
            if(args[0].getItemCount() > 1 && args[0] instanceof ValueSequence)
            {
                for(int i = 0; i < args[0].getItemCount(); i++)
                {
                    mailElements.add((Element)args[0].itemAt(i));
                }
            }
            else
            {
                mailElements.add((Element)args[0].itemAt(0));
            }

            List<Mail> mails = parseMailElement(mailElements);

            ValueSequence results = new ValueSequence();

            //Send email with Sendmail or SMTP?
            if(!args[1].isEmpty())
            {
                //SMTP
                List<Boolean> mailResults = sendBySMTP(mails, args[1].getStringValue());
                
                for(Boolean mailResult : mailResults)
                {
                    results.add(BooleanValue.valueOf(mailResult));
                }
            }
            else
            {
                for(Mail mail : mails)
                {
                   boolean result = sendBySendmail(mail);

                   results.add(BooleanValue.valueOf(result));
                }
            }
            
            return results;
        }
        catch(TransformerException te)
        {
            throw new XPathException(this, "Could not Transform XHTML Message Body: " + te.getMessage(), te);
        }
        catch(SMTPException smtpe)
        {
            throw new XPathException(this, "Could not send message(s)" + smtpe.getMessage(), smtpe);
        }
    }
	
    /**
     * Sends an email using the Operating Systems sendmail application
     *
     * @param mail representation of the email to send
     * @return		boolean value of true of false indicating success or failure to send email
     */
    private boolean sendBySendmail(Mail mail)
    {
        PrintWriter out = null;

        try
        {
            //Create a list of all Recipients, should include to, cc and bcc recipient
            List<String> allrecipients = new ArrayList<String>();

            allrecipients.addAll(mail.getTo());
            allrecipients.addAll(mail.getCC());
            allrecipients.addAll(mail.getBCC());

            //Get a string of all recipients email addresses
            String recipients = "";

            for(int x = 0; x < allrecipients.size(); x++)
            {
                //Check format of to address does it include a name as well as the email address?
                if(((String)allrecipients.get(x)).indexOf("<") != -1)
                {
                    //yes, just add the email address
                    recipients += " " + ((String)allrecipients.get(x)).substring(((String)allrecipients.get(x)).indexOf("<") + 1, ((String)allrecipients.get(x)).indexOf(">"));
                }
                else
                {
                    //add the email address
                    recipients += " " + ((String)allrecipients.get(x));
                }
            }

            //Create a sendmail Process
            Process p = Runtime.getRuntime().exec("/usr/sbin/sendmail" + recipients);

            //Get a Buffered Print Writer to the Processes stdOut
            out = new PrintWriter(new OutputStreamWriter(p.getOutputStream(),charset));

            //Send the Message
            writeMessage(out, mail);
        }
        catch(IOException e)
        {
            LOG.error(e.getMessage(), e);
            
            return false;
        }
        finally
        {
            //Close the stdOut
            if(out != null)
                out.close();
        }

        //Message Sent Succesfully
        LOG.info("send-email() message sent using Sendmail " + new Date());

        return true;
    }

    private class SMTPException extends Exception
    {
        public SMTPException(String message)
        {
            super(message);
        }

        public SMTPException(Throwable cause)
        {
            super(cause);
        }
    }

    /**
     * Sends an email using SMTP
     *
     * @param mail		A mail object representing the email to send
     * @param SMTPServer	The SMTP Server to send the email through
     * @return		boolean value of true of false indicating success or failure to send email
     */
    private List<Boolean> sendBySMTP(List<Mail> mails, String SMTPServer) throws SMTPException
    {
        final int TCP_PROTOCOL_SMTP = 25;   //SMTP Protocol
        String smtpResult = "";             //Holds the server Result code when an SMTP Command is executed

        Socket smtpSock = null;
        BufferedReader smtpIn = null;
        PrintWriter smtpOut = null;

        List<Boolean> sendMailResults = new ArrayList<Boolean>();

        try
        {
            //Create a Socket and connect to the SMTP Server
            smtpSock = new Socket(SMTPServer, TCP_PROTOCOL_SMTP);

            //Create a Buffered Reader for the Socket
            smtpIn = new BufferedReader(new InputStreamReader(smtpSock.getInputStream()));

            //Create an Output Writer for the Socket
            smtpOut = new PrintWriter(new OutputStreamWriter(smtpSock.getOutputStream(),charset));

            //First line sent to us from the SMTP server should be "220 blah blah", 220 indicates okay
            smtpResult = smtpIn.readLine();
            if(!smtpResult.substring(0, 3).toString().equals("220"))
            {
                String errMsg = "Error - SMTP Server not ready: '" + smtpResult + "'";
                LOG.error(errMsg);
                throw new SMTPException(errMsg);
            }

            //Say "HELO"
            smtpOut.println("HELO " + InetAddress.getLocalHost().getHostName());
            smtpOut.flush();

            //get "HELLO" response, should be "250 blah blah"
            smtpResult = smtpIn.readLine();
            if(!smtpResult.substring(0, 3).toString().equals("250"))
            {
                String errMsg = "Error - SMTP HELO Failed: '" + smtpResult + "'";
                LOG.error(errMsg);
                throw new SMTPException(errMsg);
            }

            //write SMTP message(s)
            for(Mail mail : mails)
            {
                boolean mailResult = writeSMTPMessage(mail, smtpOut, smtpIn);

                sendMailResults.add(mailResult);
            }
        }
        catch(IOException ioe)
        {
            LOG.error(ioe.getMessage(), ioe);
            throw new SMTPException(ioe);
        }
        finally
        {
            try {
                if(smtpOut != null)
                    smtpOut.close();

                if(smtpIn != null)
                    smtpIn.close();

                if(smtpSock != null)
                    smtpSock.close();
                
            } catch (IOException ioe) {
                LOG.warn(ioe.getMessage(), ioe);
            }
        }

        //Message(s) Sent Succesfully
        LOG.info("send-email() message(s) sent using SMTP " + new Date());

        return sendMailResults;
    }

    private boolean writeSMTPMessage(Mail mail, PrintWriter smtpOut, BufferedReader smtpIn)
    {
        try
        {
            String smtpResult = "";

            //Send "MAIL FROM:"
            //Check format of from address does it include a name as well as the email address?
            if(mail.getFrom().indexOf("<") != -1)
            {
                //yes, just send the email address
                smtpOut.println("MAIL FROM:<" + mail.getFrom().substring(mail.getFrom().indexOf("<") + 1, mail.getFrom().indexOf(">")) + ">");
            }
            else
            {
                //no, doesnt include a name so send the email address
                smtpOut.println("MAIL FROM:<" + mail.getFrom() + ">");
            }
            smtpOut.flush();

            //Get "MAIL FROM:" response
            smtpResult = smtpIn.readLine();
            if(!smtpResult.substring(0, 3).toString().equals("250"))
            {
                LOG.error("Error - SMTP MAIL FROM failed: " + smtpResult);
                return false;
            }

            //RCPT TO should be issued for each to, cc and bcc recipient
            List<String> allrecipients = new ArrayList<String>();
            allrecipients.addAll(mail.getTo());
            allrecipients.addAll(mail.getCC());
            allrecipients.addAll(mail.getBCC());

            for(int x = 0; x < allrecipients.size(); x++)
            {
                //Send "RCPT TO:"
                //Check format of to address does it include a name as well as the email address?
                if(((String)allrecipients.get(x)).indexOf("<") != -1)
                {
                    //yes, just send the email address
                    smtpOut.println("RCPT TO:<" + ((String)allrecipients.get(x)).substring(((String)allrecipients.get(x)).indexOf("<") + 1, ((String)allrecipients.get(x)).indexOf(">")) + ">");
                }
                else
                {
                    smtpOut.println("RCPT TO:<" + ((String)allrecipients.get(x)) + ">");
                }
                smtpOut.flush();

                //Get "RCPT TO:" response
                smtpResult = smtpIn.readLine();
                if(!smtpResult.substring(0, 3).toString().equals("250"))
                {
                    LOG.error("Error - SMTP RCPT TO failed: " + smtpResult);
                }
            }


            //SEND "DATA"
            smtpOut.println("DATA");
            smtpOut.flush();

            //Get "DATA" response, should be "354 blah blah"
            smtpResult = smtpIn.readLine();
            if(!smtpResult.substring(0, 3).toString().equals("354"))
            {
                LOG.error("Error - SMTP DATA failed: " + smtpResult);
                return false;
            }

            //Send the Message
            writeMessage(smtpOut, mail);

            //Get end message response, should be "250 blah blah"
            smtpResult = smtpIn.readLine();
            if(!smtpResult.substring(0, 3).toString().equals("250"))
            {
                LOG.error("Error - Message not accepted: " + smtpResult);
                return false;
            }
        }
        catch(IOException ioe)
        {
            LOG.error(ioe.getMessage(), ioe);
            return false;
        }

        return true;
    }
	
    /**
     * Writes an email payload (Headers + Body) from a mail object
     *
     * @param smtpOut		A PrintWriter to receive the email
     * @param mail		A mail object representing the email to write out
     */
    private void writeMessage(PrintWriter out, Mail aMail) throws IOException
    {
        String Version = eXistVersion();				//Version of eXist
        String MultipartBoundary = "eXist.multipart." + Version;	//Multipart Boundary

        //write the message headers

        out.println("From: " + encode64Address(aMail.getFrom()));

        if(aMail.getReplyTo() != null)
        {
            out.println("Reply-To: " + encode64Address(aMail.getReplyTo()));
        }

        for(int x = 0; x < aMail.countTo(); x++)
        {
            out.println("To: " + encode64Address(aMail.getTo(x)));
        }

        for(int x = 0; x < aMail.countCC(); x++)
        {
            out.println("CC: " + encode64Address(aMail.getCC(x)));
        }
        
        for(int x = 0; x < aMail.countBCC(); x++)
        {
            out.println("BCC: " + encode64Address(aMail.getBCC(x)));
        }
        
        out.println("Date: " + getDateRFC822());
        out.println("Subject: " + encode64(aMail.getSubject()));
        out.println("X-Mailer: eXist " + Version + " mail:send-email()");
        out.println("MIME-Version: 1.0");


        boolean multipartAlternative = false;
        String multipartBoundary = null;

        if(aMail.attachmentIterator().hasNext())
        {
            // we have an attachment as well as text and/or html so we need a multipart/mixed message
            multipartBoundary =  MultipartBoundary;
        }
        else if(!aMail.getText().equals("") && !aMail.getXHTML().equals(""))
        {
            // we have text and html so we need a multipart/alternative message and no attachment
            multipartAlternative = true;
            multipartBoundary = MultipartBoundary + "_alt";
        }
        else
        {
            // we have either text or html and no attachment this message is not multipart
        }

        //content type
        if(multipartBoundary != null)
        {
            //multipart message

            out.println("Content-Type: " + (multipartAlternative ? "multipart/alternative" : "multipart/mixed") + "; boundary=\"" + multipartBoundary + "\";");

            //Mime warning
            out.println();
            out.println("Error your mail client is not MIME Compatible");

            out.println("--" + multipartBoundary);
        }

        // TODO - need to put out a multipart/mixed boundary here when HTML, text and attachment present
        if(!aMail.getText().toString().equals("") && !aMail.getXHTML().toString().equals("") && aMail.attachmentIterator().hasNext())
        {
            out.println("Content-Type: multipart/alternative; boundary=\"" + MultipartBoundary + "_alt\";");
            out.println("--" + MultipartBoundary + "_alt");
        }

        //text email
        if(!aMail.getText().toString().equals(""))
        {
            out.println("Content-Type: text/plain; charset=" + charset);
            out.println("Content-Transfer-Encoding: 8bit");

            //now send the txt message
            out.println();
            out.println(aMail.getText());

            if(multipartBoundary != null)
            {
                if(!aMail.getXHTML().toString().equals("") || aMail.attachmentIterator().hasNext())
                {
                    if(!aMail.getText().toString().equals("") && !aMail.getXHTML().toString().equals("") && aMail.attachmentIterator().hasNext())
                    {
                        out.println("--" + MultipartBoundary + "_alt");
                    }
                    else
                    {
                        out.println("--" + multipartBoundary);
                    }
                }
                else
                {
                    if(!aMail.getText().toString().equals("") && !aMail.getXHTML().toString().equals("") && aMail.attachmentIterator().hasNext())
                    {
                        out.println("--" + MultipartBoundary + "_alt--");
                    }
                    else
                    {
                        out.println("--" + multipartBoundary + "--");
                    }
                }
            }
        }

        //HTML email
        if(!aMail.getXHTML().toString().equals(""))
        {
                out.println("Content-Type: text/html; charset=" + charset);
                out.println("Content-Transfer-Encoding: 8bit");

                //now send the html message
                out.println();
                out.println("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">");
                out.println(aMail.getXHTML());

                if(multipartBoundary != null)
                {
                    if(aMail.attachmentIterator().hasNext())
                    {
                        if(!aMail.getText().toString().equals("") && !aMail.getXHTML().toString().equals("") && aMail.attachmentIterator().hasNext())
                        {
                            out.println("--" + MultipartBoundary + "_alt--");
                            out.println("--" + multipartBoundary);
                        }
                        else
                        {
                            out.println("--" + multipartBoundary);
                        }
                    }
                    else
                    {
                        if(!aMail.getText().toString().equals("") && !aMail.getXHTML().toString().equals("") && aMail.attachmentIterator().hasNext())
                        {
                            out.println("--" + MultipartBoundary + "_alt--");
                        }
                        else
                        {
                            out.println("--" + multipartBoundary + "--");
                        }
                    }
                }
        }

        //attachments
        if(aMail.attachmentIterator().hasNext())
        {
            for(Iterator<MailAttachment> itAttachment = aMail.attachmentIterator(); itAttachment.hasNext(); )
            {
                MailAttachment ma = itAttachment.next();

                out.println("Content-Type: " + ma.getMimeType() + "; name=\"" + ma.getFilename() + "\"");
                out.println("Content-Transfer-Encoding: base64");
                out.println("Content-Description: " + ma.getFilename());
                out.println("Content-Disposition: attachment; filname=\"" + ma.getFilename() + "\"");
                out.println();
                out.println(ma.getData());

                if(itAttachment.hasNext())
                {
                    out.println("--" + multipartBoundary);
                }
            }

            //Emd multipart message
            out.println("--" + multipartBoundary + "--");
        }

        //end the message, <cr><lf>.<cr><lf>
        out.println();
        out.println(".");
        out.flush();
    }


    /**
     * Get's the version of eXist we are running
     * The eXist version is used as part of the multipart separator
     *
     * @return		The eXist Version
     */
    private String eXistVersion() throws IOException
    {
        Properties sysProperties = new Properties();
        sysProperties.load(GetVersion.class.getClassLoader().getResourceAsStream("org/exist/system.properties"));
        return((String)sysProperties.getProperty("product-version", "unknown version"));
    }
	
    /**
     * Constructs a mail Object from an XML representation of an email
     *
     * The XML email Representation is expected to look something like this
     *
     * <mail>
     * 	<from></from>
     * 	<reply-to></reply-to>
     * 	<to></to>
     * 	<cc></cc>
     * 	<bcc></bcc>
     * 	<subject></subject>
     * 	<message>
     * 		<text></text>
     * 		<xhtml></xhtml>
     * 	</message>
     * </mail>
     *
     * @param mailElements	The XML mail Node
     * @return		A mail Object representing the XML mail Node
     */
    private List<Mail> parseMailElement(List<Element> mailElements) throws TransformerException
    {
        List<Mail> mails = new ArrayList<Mail>();

        for(Element mailElement : mailElements)
        {
            //Make sure that message has a Mail node
            if(mailElement.getLocalName().equals("mail"))
            {
                //New mail Object
                Mail mail = new Mail();

                //Get the First Child
                Node child = mailElement.getFirstChild();
                while(child != null)
                {
                    //Parse each of the child nodes
                    if(child.getNodeType() == Node.ELEMENT_NODE && child.hasChildNodes())
                    {
                        if(child.getLocalName().equals("from"))
                        {
                                mail.setFrom(child.getFirstChild().getNodeValue());
                        }
                        if(child.getLocalName().equals("reply-to"))
                        {
                                mail.setReplyTo(child.getFirstChild().getNodeValue());
                        }
                        else if(child.getLocalName().equals("to"))
                        {
                                mail.addTo(child.getFirstChild().getNodeValue());
                        }
                        else if(child.getLocalName().equals("cc"))
                        {
                                mail.addCC(child.getFirstChild().getNodeValue());
                        }
                        else if(child.getLocalName().equals("bcc"))
                        {
                                mail.addBCC(child.getFirstChild().getNodeValue());
                        }
                        else if(child.getLocalName().equals("subject"))
                        {
                                mail.setSubject(child.getFirstChild().getNodeValue());
                        }
                        else if(child.getLocalName().equals("message"))
                        {
                            //If the message node, then parse the child text and xhtml nodes
                            Node bodyPart = child.getFirstChild();
                            while(bodyPart != null)
                            {
                                if(bodyPart.getLocalName().equals("text"))
                                {
                                        mail.setText(bodyPart.getFirstChild().getNodeValue());
                                }
                                else if(bodyPart.getLocalName().equals("xhtml"))
                                {
                                    //Convert everything inside <xhtml></xhtml> to text
                                    TransformerFactory transFactory = TransformerFactory.newInstance();
                                    Transformer transformer = transFactory.newTransformer();
                                    DOMSource source = new DOMSource(bodyPart.getFirstChild());
                                    StringWriter strWriter = new StringWriter();
                                    StreamResult result = new StreamResult(strWriter);
                                    transformer.transform(source, result);

                                    mail.setXHTML(strWriter.toString());
                                }

                                //next body part
                                bodyPart = bodyPart.getNextSibling();
                            }

                        }
                        else if(child.getLocalName().equals("attachment"))
                        {
                            Element attachment = (Element)child;
                            MailAttachment ma = new MailAttachment(attachment.getAttribute("filename"), attachment.getAttribute("mimetype"), attachment.getFirstChild().getNodeValue());
                            mail.addAttachment(ma);
                        }
                    }

                    //next node
                    child = child.getNextSibling();

                }
                mails.add(mail);
            }
        }

        return mails;
    }
	
    /**
     * Returns the current date and time in an RFC822 format, suitable for an email Date Header
     *
     * @return		RFC822 formated date and time as a String
     */
    private String getDateRFC822()
    {
        String dateString = new String();
        Calendar rightNow = Calendar.getInstance();

        //Day of the week
        switch(rightNow.get(Calendar.DAY_OF_WEEK))
        {
            case Calendar.MONDAY:
            {
                    dateString = "Mon";
                    break;
            }
            case Calendar.TUESDAY:
            {
                    dateString = "Tue";
                    break;
            }
            case Calendar.WEDNESDAY:
            {
                    dateString = "Wed";
                    break;
            }
            case Calendar.THURSDAY:
            {
                    dateString = "Thu";
                    break;
            }
            case Calendar.FRIDAY:
            {
                    dateString = "Fri";
                    break;
            }
            case Calendar.SATURDAY:
            {
                    dateString = "Sat";
                    break;
            }
            case Calendar.SUNDAY:
            {
                    dateString = "Sun";
                    break;
            }
        }
        dateString += ", ";

        //Date
        dateString += rightNow.get(Calendar.DAY_OF_MONTH);
        dateString += " ";

        //Month
        switch(rightNow.get(Calendar.MONTH))
        {
            case Calendar.JANUARY:
            {
                    dateString += "Jan";
                    break;
            }
            case Calendar.FEBRUARY:
            {
                    dateString += "Feb";
                    break;
            }
            case Calendar.MARCH:
            {
                    dateString += "Mar";
                    break;
            }
            case Calendar.APRIL:
            {
                    dateString += "Apr";
                    break;
            }
            case Calendar.MAY:
            {
                    dateString += "May";
                    break;
            }
            case Calendar.JUNE:
            {
                    dateString += "Jun";
                    break;
            }
            case Calendar.JULY:
            {
                    dateString += "Jul";
                    break;
            }
            case Calendar.AUGUST:
            {
                    dateString += "Aug";
                    break;
            }
            case Calendar.SEPTEMBER:
            {
                    dateString += "Sep";
                    break;
            }
            case Calendar.OCTOBER:
            {
                    dateString += "Oct";
                    break;
            }
            case Calendar.NOVEMBER:
            {
                    dateString += "Nov";
                    break;
            }
            case Calendar.DECEMBER:
            {
                    dateString += "Dec";
                    break;
            }
        }
        dateString += " ";

        //Year
        dateString += rightNow.get(Calendar.YEAR);
        dateString += " ";

        //Time
        String tHour = Integer.toString(rightNow.get(Calendar.HOUR_OF_DAY));
        if(tHour.length() == 1)
        {
                tHour = "0" + tHour;
        }
        String tMinute = Integer.toString(rightNow.get(Calendar.MINUTE));
        if(tMinute.length() == 1)
        {
                tMinute = "0" + tMinute;
        }
        String tSecond = Integer.toString(rightNow.get(Calendar.SECOND));
        if(tSecond.length() == 1)
        {
                tSecond = "0" + tSecond;
        }

        dateString += tHour + ":" + tMinute + ":" + tSecond + " ";

        //TimeZone Correction
        String tzSign = new String();
        String tzHours = new String();
        String tzMinutes = new String();

        TimeZone thisTZ = TimeZone.getDefault();
        int TZOffset = thisTZ.getOffset(rightNow.get(Calendar.DATE)); //get timezone offset in milliseconds
        TZOffset = (TZOffset / 1000); //convert to seconds
        TZOffset = (TZOffset / 60); //convert to minutes

        //Sign
        if(TZOffset > 1)
        {
            tzSign = "+";
        }
        else
        {
            tzSign = "-";
        }

        //Calc Hours and Minutes?
        if(TZOffset >= 60 || TZOffset <= -60)
        {
            //Minutes and Hours
            tzHours += (TZOffset / 60); //hours
            if(tzHours.length() == 1)  // do we need to prepend a 0
            {
                    tzHours = "0" + tzHours;
            }

            tzMinutes += (TZOffset % 60); //minutes
            if(tzMinutes.length() == 1)  // do we need to prepend a 0
            {
                    tzMinutes = "0" + tzMinutes;
            }
        }
        else
        {
            //Just Minutes
            tzHours = "00";
            tzMinutes += TZOffset;
            if(tzMinutes.length() == 1)  // do we need to prepend a 0
            {
                    tzMinutes = "0" + tzMinutes;
            }
        }

        dateString += tzSign + tzHours + tzMinutes;

        return(dateString);
    }
	
    /**
     * Base64 Encodes a string (used for message subject)
     *
     * @param str	The String to encode
     * @return		The encoded String
     */
    private String encode64 (String str) throws java.io.UnsupportedEncodingException
    {
        Base64Encoder enc = new Base64Encoder();
        enc.translate(str.getBytes(charset));
        String result = new String(enc.getCharArray());

        result = result.replaceAll("\n","?=\n =?" + charset + "?B?");
        result = "=?" + charset + "?B?" + result + "?=";
        return(result);
    }

    /**
     * Base64 Encodes an email address
     *
     * @param str	The email address as a String to encode
     * @return		The encoded email address String
     */
    private String encode64Address (String str) throws java.io.UnsupportedEncodingException
    {
        String result;
        int idx = str.indexOf("<");

        if(idx != -1)
        {
            result = encode64(str.substring(0,idx)) + " " + str.substring(idx);
        }
        else
        {
            result = str;
        }

        return(result);
    }

    /**
     * A simple data class to represent an email
     * attachment. Just has private
     * members and some get methods.
     *
     * @version 1.2
     */
    private class MailAttachment
    {
        private String filename;
        private String mimeType;
        private String data;

        public MailAttachment(String filename, String mimeType, String data)
        {
                this.filename = filename;
                this.mimeType = mimeType;
                this.data = data;
        }

        public String getData() {
                return data;
        }

        public String getFilename() {
                return filename;
        }

        public String getMimeType() {
                return mimeType;
        }
    }
	
    /**
     * A simple data class to represent an email
     * doesnt do anything fancy just has private
     * members and get and set methods
     *
     * @version 1.2
     */
    private class Mail
    {
        private String from = "";				//Who is the mail from
        private String replyTo = null;                          //Who should you reply to
        private List<String> to = new ArrayList<String>();      //Who is the mail going to
        private List<String> cc = new ArrayList<String>();	//Carbon Copy to
        private List<String> bcc = new ArrayList<String>();	//Blind Carbon Copy to
        private String subject = "";				//Subject of the mail
        private String text = "";				//Body text of the mail
        private String xhtml = "";                              //Body XHTML of the mail
        private List<MailAttachment> attachment = new ArrayList<MailAttachment>();	//Any attachments

        //From
        public void setFrom(String from)
        {
            this.from = from;
        }

        public String getFrom()
        {
            return(this.from);
        }

        //reply-to
        public void setReplyTo(String replyTo)
        {
            this.replyTo = replyTo;
        }

        public String getReplyTo()
        {
            return replyTo;
        }

        //To
        public void addTo(String to)
        {
            this.to.add(to);
        }

        public int countTo()
        {
            return(to.size());
        }

        public String getTo(int index)
        {
            return to.get(index);
        }

        public List<String> getTo()
        {
            return(to);
        }

        //CC
        public void addCC(String cc)
        {
            this.cc.add(cc);
        }

        public int countCC()
        {
            return(cc.size());
        }

        public String getCC(int index)
        {
            return cc.get(index);
        }

        public List<String> getCC()
        {
                return(cc);
        }

        //BCC
        public void addBCC(String bcc)
        {
            this.bcc.add(bcc);
        }

        public int countBCC()
        {
            return bcc.size();
        }

        public String getBCC(int index)
        {
            return bcc.get(index);
        }

        public List<String> getBCC()
        {
            return(bcc);
        }

        //Subject
        public void setSubject(String subject)
        {
            this.subject = subject;
        }

        public String getSubject()
        {
            return subject;
        }

        //text
        public void setText(String text)
        {
            this.text = text;
        }

        public String getText()
        {
            return text;
        }

        //xhtml
        public void setXHTML(String xhtml)
        {
            this.xhtml = xhtml;
        }

        public String getXHTML()
        {
            return xhtml;
        }

        public void addAttachment(MailAttachment ma)
        {
                attachment.add(ma);
        }

        public Iterator<MailAttachment> attachmentIterator()
        {
                return attachment.iterator();
        }
    }
}
