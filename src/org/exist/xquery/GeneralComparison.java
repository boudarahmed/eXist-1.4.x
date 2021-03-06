/*
 *  eXist Open Source Native XML Database
 * 
 *  Copyright (C) 2000-03, Wolfgang M. Meier (meier@ifs. tu- darmstadt. de)
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Library General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Library General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * $Id$
 */
package org.exist.xquery;

import org.exist.xquery.pragmas.Optimize;
import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.dom.ContextItem;
import org.exist.dom.DocumentSet;
import org.exist.dom.NewArrayNodeSet;
import org.exist.dom.NodeProxy;
import org.exist.dom.NodeSet;
import org.exist.dom.QName;
import org.exist.dom.VirtualNodeSet;
import org.exist.storage.DBBroker;
import org.exist.storage.ElementValue;
import org.exist.storage.IndexSpec;
import org.exist.storage.Indexable;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.Type;

import java.text.Collator;
import java.util.Iterator;
import java.util.List;

/**
 * A general XQuery/XPath2 comparison expression.
 * 
 * @author wolf
 */
public class GeneralComparison extends BinaryOp implements Optimizable, IndexUseReporter {

	/**
	 * The type of operator used for the comparison, i.e. =, !=, &lt;, &gt; ...
	 * One of the constants declared in class {@link Constants}.
	 */
	protected int relation = Constants.EQ;
	
	/**
	 * Truncation flags: when comparing with a string value, the search
	 * string may be truncated with a single * wildcard. See the constants declared
	 * in class {@link Constants}.
	 * 
	 * The standard functions starts-with, ends-with and contains are
	 * transformed into a general comparison with wildcard. Hence the need
	 * to consider wildcards here.
	 */
	protected int truncation = Constants.TRUNC_NONE;
	
	/**
	 * The class might cache the entire results of a previous execution.
	 */
	protected CachedResult cached = null;

	/**
	 * Extra argument (to standard functions starts-with/contains etc.)
	 * to indicate the collation to be used for string comparisons.
	 */
	protected Expression collationArg = null;
	
	/**
	 * Set to true if this expression is called within the where clause
	 * of a FLWOR expression.
	 */
	protected boolean inWhereClause = false;
    
    protected boolean invalidNodeEvaluation = false;

    protected int rightOpDeps;
    
    private boolean hasUsedIndex = false;
    
    private int actualReturnType = Type.ITEM;

    private LocationStep contextStep = null;
    private QName contextQName = null;
    protected boolean optimizeSelf = false;
    
    private int axis = Constants.UNKNOWN_AXIS;
    private NodeSet preselectResult = null;

    private IndexFlags idxflags = new IndexFlags();
    
    public GeneralComparison(XQueryContext context, int relation) {
		this(context, relation, Constants.TRUNC_NONE);
	}
	
	public GeneralComparison(XQueryContext context, int relation, int truncation) {
		super(context);
		this.relation = relation;
	}

	public GeneralComparison(XQueryContext context, Expression left, Expression right, int relation) {
		this(context, left, right, relation, Constants.TRUNC_NONE);
	}

    public GeneralComparison(XQueryContext context,	Expression left, Expression right, int relation,
            int truncation) {
		super(context);
        boolean didLeftSimplification = false;
        boolean didRightSimplification = false;
		this.relation = relation;
		this.truncation = truncation;
		if (left instanceof PathExpr && ((PathExpr) left).getLength() == 1) {
			left = ((PathExpr) left).getExpression(0);
            didLeftSimplification = true;
		}
		add(left);
		if (right instanceof PathExpr && ((PathExpr) right).getLength() == 1) {
            right = ((PathExpr) right).getExpression(0);
            didRightSimplification = true;
		}
		add(right);
        //TODO : should we also use simplify() here ? -pb
		if (didLeftSimplification)
            context.getProfiler().message(this, Profiler.OPTIMIZATIONS, "OPTIMIZATION",
            "Marked left argument as a child expression");
        if (didRightSimplification)
            context.getProfiler().message(this, Profiler.OPTIMIZATIONS, "OPTIMIZATION",
            "Marked right argument as a child expression");
	}

    /* (non-Javadoc)
     * @see org.exist.xquery.BinaryOp#analyze(org.exist.xquery.AnalyzeContextInfo)
     */
    public void analyze(AnalyzeContextInfo contextInfo) throws XPathException {
        contextInfo.addFlag(NEED_INDEX_INFO);
        contextInfo.setParent(this);
        super.analyze(contextInfo);
        inWhereClause = (contextInfo.getFlags() & IN_WHERE_CLAUSE) != 0;

        //Ugly workaround for the polysemy of "." which is expanded as self::node() even when it is not relevant
        // (1)[.= 1] works...
        invalidNodeEvaluation = false;
        if (!Type.subTypeOf(contextInfo.getStaticType(), Type.NODE))
    		invalidNodeEvaluation = getLeft() instanceof LocationStep && ((LocationStep)getLeft()).axis == Constants.SELF_AXIS;

        //Unfortunately, we lose the possibility to make a nodeset optimization
        //(we still don't know anything about the contextSequence that will be processed)

        // check if the right-hand operand is a simple cast expression
        // if yes, use the dependencies of the casted expression to compute
        // optimizations
        rightOpDeps = getRight().getDependencies();
        getRight().accept(new BasicExpressionVisitor() {
        	public void visitCastExpr(CastExpression expression) {
        		if (LOG.isTraceEnabled())
        			LOG.debug("Right operand is a cast expression");
        		rightOpDeps = expression.getInnerExpression().getDependencies();
        	}
        });
        if (contextInfo.getContextStep() != null && contextInfo.getContextStep() instanceof LocationStep) {
            ((LocationStep)contextInfo.getContextStep()).setUseDirectAttrSelect(false);
        }
        contextInfo.removeFlag(NEED_INDEX_INFO);

        List steps = BasicExpressionVisitor.findLocationSteps(getLeft());
        if (!steps.isEmpty()) {
            LocationStep firstStep = (LocationStep) steps.get(0);
            LocationStep lastStep = (LocationStep) steps.get(steps.size() - 1);
            if (steps.size() == 1 && firstStep.getAxis() == Constants.SELF_AXIS) {
                Expression outerExpr = contextInfo.getContextStep();
                if (outerExpr != null && outerExpr instanceof LocationStep) {
                    LocationStep outerStep = (LocationStep) outerExpr;
                    NodeTest test = outerStep.getTest();
                    if (!test.isWildcardTest() && test.getName() != null) {
                        contextQName = new QName(test.getName());
                        if (outerStep.getAxis() == Constants.ATTRIBUTE_AXIS || outerStep.getAxis() == Constants.DESCENDANT_ATTRIBUTE_AXIS)
                            contextQName.setNameType(ElementValue.ATTRIBUTE);
                        contextStep = firstStep;
                        axis = outerStep.getAxis();
                        optimizeSelf = true;
                    }
                }
            } else {
                NodeTest test = lastStep.getTest();
                if (!test.isWildcardTest() && test.getName() != null) {
                    contextQName = new QName(test.getName());
                    if (lastStep.getAxis() == Constants.ATTRIBUTE_AXIS || lastStep.getAxis() == Constants.DESCENDANT_ATTRIBUTE_AXIS)
                        contextQName.setNameType(ElementValue.ATTRIBUTE);
                    contextStep = lastStep;
                    axis = firstStep.getAxis();
                    if (axis == Constants.SELF_AXIS && steps.size() > 1)
                        axis = ((LocationStep) steps.get(1)).getAxis();
                }
            }
        }
    }

    public boolean canOptimize(Sequence contextSequence) {
        if (contextQName == null)
            return false;
        return Optimize.getQNameIndexType(context, contextSequence, contextQName) != Type.ITEM;
    }

    public boolean optimizeOnSelf() {
        return optimizeSelf;
    }

    public int getOptimizeAxis() {
        return axis;
    }

    /* (non-Javadoc)
	 * @see org.exist.xquery.BinaryOp#returnsType()
	 */
	public int returnsType() {
        if (inPredicate && (!Dependency.dependsOn(this, Dependency.CONTEXT_ITEM))) {
            return getLeft().returnsType();
        }
		// In all other cases, we return boolean
		return Type.BOOLEAN;
	}

    /* (non-Javadoc)
	 * @see org.exist.xquery.AbstractExpression#getDependencies()
	 */
	public int getDependencies() {
		// left expression returns node set
		if (Type.subTypeOf(getLeft().returnsType(), Type.NODE) &&
			//	and does not depend on the context item
			!Dependency.dependsOn(getLeft(), Dependency.CONTEXT_ITEM) &&
			(!inWhereClause || !Dependency.dependsOn(getLeft(), Dependency.CONTEXT_VARS)))
		{
			return Dependency.CONTEXT_SET;
		} else {
			return Dependency.CONTEXT_SET + Dependency.CONTEXT_ITEM;
		}
	}

    public int getRelation() {
        return this.relation;
    }
    
    public NodeSet preSelect(Sequence contextSequence, boolean useContext) throws XPathException {
        // the expression can be called multiple times, so we need to clear the previous preselectResult
        preselectResult = null;
        long start = System.currentTimeMillis();
        int indexType = Optimize.getQNameIndexType(context, contextSequence, contextQName);
        if (LOG.isTraceEnabled())
            LOG.trace("Using QName index on type " + Type.getTypeName(indexType));
        Sequence rightSeq = getRight().eval(contextSequence);

        if (rightSeq.getItemCount() > 1)
        	preselectResult = new NewArrayNodeSet();
        
        for (SequenceIterator itRightSeq = rightSeq.iterate(); itRightSeq.hasNext();) {
            //Get the index key
            Item key = itRightSeq.nextItem().atomize();

            //if key has truncation, convert it to string
            if(truncation != Constants.TRUNC_NONE) {
            	if (!Type.subTypeOf(key.getType(), Type.STRING)) {
            		LOG.info("Truncated key. Converted from " + Type.getTypeName(key.getType()) + " to xs:string");
            		//truncation is only possible on strings
            		key = key.convertTo(Type.STRING);
            	}
            }
            //else if key is not the same type as the index
            //TODO : use Type.isSubType() ??? -pb
            else if (key.getType() != indexType) {
                //try to convert the key to the index type
                try	{
                    key = key.convertTo(indexType);
                } catch(XPathException xpe)	{
                    if (LOG.isTraceEnabled())
                    	LOG.trace("Cannot convert key: " + Type.getTypeName(key.getType()) + " to required index type: " + Type.getTypeName(indexType));

                    throw new XPathException(this, "Cannot convert key to required index type");
                }
            }

            // If key implements org.exist.storage.Indexable, we can use the index
            if (key instanceof Indexable) {
                if (LOG.isTraceEnabled())
                    LOG.trace("Using QName range index for key: " + key.getStringValue());
                NodeSet temp;
                NodeSet contextSet = useContext ? contextSequence.toNodeSet() : null;
                if(truncation == Constants.TRUNC_NONE) {
                    temp =
                        context.getBroker().getValueIndex().find(relation, contextSequence.getDocumentSet(),
                                contextSet, NodeSet.DESCENDANT, contextQName, (Indexable)key);
                    hasUsedIndex = true;
                } else {
                    try {
                        temp = context.getBroker().getValueIndex().match(contextSequence.getDocumentSet(), contextSet,
                                NodeSet.DESCENDANT, getRegexp(key.getStringValue()).toString(),
                                contextQName, DBBroker.MATCH_REGEXP);
                        hasUsedIndex = true;
                    } catch (EXistException e) {
                        throw new XPathException(this, "Error during index lookup: " + e.getMessage(), e);
                    }
                }
                if (preselectResult == null)
                    preselectResult = temp;
                else {
                    preselectResult.addAll(temp);
                }
            }
        }
        if (context.getProfiler().traceFunctions())
            context.getProfiler().traceIndexUsage(context, PerformanceStats.RANGE_IDX_TYPE, this,
                PerformanceStats.OPTIMIZED_INDEX, System.currentTimeMillis() - start);
        return preselectResult == null ? NodeSet.EMPTY_SET : preselectResult;
    }

    /* (non-Javadoc)
	 * @see org.exist.xquery.Expression#eval(org.exist.xquery.StaticContext, org.exist.dom.DocumentSet, org.exist.xquery.value.Sequence, org.exist.xquery.value.Item)
	 */
	public Sequence eval(Sequence contextSequence, Item contextItem) throws XPathException {
		if (context.getProfiler().isEnabled()) {
            context.getProfiler().start(this);
            context.getProfiler().message(this, Profiler.DEPENDENCIES, "DEPENDENCIES", Dependency.getDependenciesName(this.getDependencies()));
            if (contextSequence != null)
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT SEQUENCE", contextSequence);
            if (contextItem != null)
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT ITEM", contextItem.toSequence());
        }

        Sequence result;
        
        // if the context sequence hasn't changed we can return a cached result
		if (cached != null && cached.isValid(contextSequence, contextItem)) {
			LOG.debug("Using cached results");
            if(context.getProfiler().isEnabled())
                context.getProfiler().message(this, Profiler.OPTIMIZATIONS, "OPTIMIZATION", "Returned cached result");
			result = cached.getResult();
		
		} else {

			// if we were optimizing and the preselect did not return anything,
	        // we won't have any matches and can return
	        if (preselectResult != null && preselectResult.isEmpty())
	            result = Sequence.EMPTY_SEQUENCE;	        
	        else {
		        if (contextStep == null || preselectResult == null) {
		            /*
		             * If we are inside a predicate and one of the arguments is a node set,
		             * we try to speed up the query by returning nodes from the context set.
		             * This works only inside a predicate. The node set will always be the left
		             * operand.
		             */
		            if (inPredicate && !invalidNodeEvaluation &&
		                    !Dependency.dependsOn(this, Dependency.CONTEXT_ITEM) &&
		                    Type.subTypeOf(getLeft().returnsType(), Type.NODE) &&
                            (contextSequence == null || contextSequence.isPersistentSet())) {
		
		                if(contextItem != null)
		                    contextSequence = contextItem.toSequence();
		
		                if ((!Dependency.dependsOn(rightOpDeps, Dependency.CONTEXT_ITEM))) {
		                    result = quickNodeSetCompare(contextSequence);
		                } else {
		            		NodeSet nodes = (NodeSet) getLeft().eval(contextSequence);
		                    result = nodeSetCompare(nodes, contextSequence);
		                }
		            } else {
		                result = genericCompare(contextSequence, contextItem);
		            }
		        } else {
                    contextStep.setPreloadedData(preselectResult.getDocumentSet(), preselectResult);		
		            result = getLeft().eval(contextSequence).toNodeSet();
		        }
		    }
	        
			// can this result be cached? Don't cache if the result depends on local variables.
		    boolean canCache = contextSequence != null && contextSequence.isCacheable() &&
		    	!Dependency.dependsOn(getLeft(), Dependency.CONTEXT_ITEM) &&
		    	!Dependency.dependsOn(getRight(), Dependency.CONTEXT_ITEM) &&
		    	!Dependency.dependsOnVar(getLeft()) &&
		    	!Dependency.dependsOnVar(getRight());

		    if(canCache)
				cached = new CachedResult(contextSequence, contextItem, result);

		}
		
        if (context.getProfiler().isEnabled())
            context.getProfiler().end(this, "", result);
        
        actualReturnType = result.getItemType();

        return result;
	}

    /**
	 * Generic, slow implementation. Applied if none of the possible
	 * optimizations can be used.
	 *
	 * @param contextSequence
	 * @param contextItem
	 * @return The Sequence resulting from the comparison
	 * @throws XPathException
	 */
	protected Sequence genericCompare(Sequence contextSequence,	Item contextItem) throws XPathException {
        if (context.getProfiler().isEnabled())
            context.getProfiler().message(this, Profiler.OPTIMIZATION_FLAGS,
                    "OPTIMIZATION CHOICE", "genericCompare");
		final Sequence ls = getLeft().eval(contextSequence, contextItem);
		return genericCompare(ls, contextSequence, contextItem);
	}

    protected Sequence genericCompare(Sequence ls, Sequence contextSequence, Item contextItem) throws XPathException {
        long start = System.currentTimeMillis();
        final Sequence rs = getRight().eval(contextSequence, contextItem);
		final Collator collator = getCollator(contextSequence);
        Sequence result = BooleanValue.FALSE;
		if (ls.isEmpty() && rs.isEmpty()) {
			result = BooleanValue.valueOf(compareAtomic(collator, AtomicValue.EMPTY_VALUE, AtomicValue.EMPTY_VALUE));
		} else if (ls.isEmpty() && !rs.isEmpty()) {
			for (SequenceIterator i2 = rs.iterate(); i2.hasNext();) {
				if (compareAtomic(collator, AtomicValue.EMPTY_VALUE, i2.nextItem().atomize())) {
					result = BooleanValue.TRUE;
                    break;
                }
			}
		} else if (!ls.isEmpty()&& rs.isEmpty()) {
			for (SequenceIterator i1 = ls.iterate(); i1.hasNext();) {
				AtomicValue lv = i1.nextItem().atomize();
				if (compareAtomic(collator, lv, AtomicValue.EMPTY_VALUE)) {
					result = BooleanValue.TRUE;
                    break;
                }
			}
		} else if (ls.hasOne() && rs.hasOne()) {
			result = BooleanValue.valueOf(compareAtomic(collator, ls.itemAt(0).atomize(), rs.itemAt(0).atomize()));
		} else {
			for (SequenceIterator i1 = ls.iterate(); i1.hasNext();) {
				AtomicValue lv = i1.nextItem().atomize();
				if (rs.isEmpty()) {
					if (compareAtomic(collator, lv, AtomicValue.EMPTY_VALUE)) {
						result = BooleanValue.TRUE;
                        break;
                    }
				} else if (rs.hasOne()) {
					if (compareAtomic(collator, lv, rs.itemAt(0).atomize())) {
						//return early if we are successful, continue otherwise
						result = BooleanValue.TRUE;
                        break;
                    }
				} else {
					for (SequenceIterator i2 = rs.iterate(); i2.hasNext();) {
						if (compareAtomic(collator, lv, i2.nextItem().atomize())) {
							result = BooleanValue.TRUE;
                            break;
                        }
					}
				}
			}
		}
        if (context.getProfiler().traceFunctions())
            context.getProfiler().traceIndexUsage(context, PerformanceStats.RANGE_IDX_TYPE, this,
                PerformanceStats.NO_INDEX, System.currentTimeMillis() - start);
		return result;
    }

    /**
	 * Optimized implementation, which can be applied if the left operand
	 * returns a node set. In this case, the left expression is executed first.
	 * All matching context nodes are then passed to the right expression.
	 */
    protected Sequence nodeSetCompare(NodeSet nodes, Sequence contextSequence) throws XPathException {
        if (context.getProfiler().isEnabled())
            context.getProfiler().message(this, Profiler.OPTIMIZATION_FLAGS, "OPTIMIZATION CHOICE", "nodeSetCompare");
        if (LOG.isTraceEnabled())
        	LOG.trace("No index: fall back to nodeSetCompare");
        long start = System.currentTimeMillis();
		NodeSet result = new NewArrayNodeSet();
		final Collator collator = getCollator(contextSequence);
		if (contextSequence != null && !contextSequence.isEmpty() && !contextSequence.getDocumentSet().contains(nodes.getDocumentSet()))
		{
			for (Iterator i1 = nodes.iterator(); i1.hasNext();) {
				NodeProxy item = (NodeProxy) i1.next();
				ContextItem context = item.getContext();
				if (context == null)
					throw new XPathException(this, "Internal error: context node missing");
				AtomicValue lv = item.atomize();
				do
				{
					Sequence rs = getRight().eval(context.getNode().toSequence());
					for (SequenceIterator i2 = rs.iterate(); i2.hasNext();) {
						AtomicValue rv = i2.nextItem().atomize();
						if (compareAtomic(collator, lv, rv))
							result.add(item);
					}
				} while ((context = context.getNextDirect()) != null);
			}
		} else { 
			for (Iterator i1 = nodes.iterator(); i1.hasNext();) {
		    	NodeProxy item = (NodeProxy) i1.next();
				AtomicValue lv = item.atomize();
				Sequence rs = getRight().eval(contextSequence);				
				for (SequenceIterator i2 = rs.iterate(); i2.hasNext();)	{
					AtomicValue rv = i2.nextItem().atomize();
					if (compareAtomic(collator, lv, rv))
						result.add(item);
				}
		    }
		}
        if (context.getProfiler().traceFunctions())
            context.getProfiler().traceIndexUsage(context, PerformanceStats.RANGE_IDX_TYPE, this,
                PerformanceStats.NO_INDEX, System.currentTimeMillis() - start);
		return result;
	}

    /**
	 * Optimized implementation: first checks if a range index is defined
	 * on the nodes in the left argument. If that fails, check if we can use
	 * the fulltext index to speed up the search. Otherwise, fall back to
	 * {@link #nodeSetCompare(NodeSet, Sequence)}.
	 */
	protected Sequence quickNodeSetCompare(Sequence contextSequence) throws XPathException {

		/* TODO think about optimising fallback to NodeSetCompare() in the for loop!!!
		 * At the moment when we fallback to NodeSetCompare() we are in effect throwing away any nodes
		 * we have already processed in quickNodeSetCompare() and reprocessing all the nodes in NodeSetCompare().
		 * Instead - Could we create a NodeCompare() (based on NodeSetCompare() code) to only compare a single node and then union the result?
		 * - deliriumsky
		 */

		if (context.getProfiler().isEnabled())
			context.getProfiler().message(this, Profiler.OPTIMIZATION_FLAGS, "OPTIMIZATION CHOICE", "quickNodeSetCompare");

        long start = System.currentTimeMillis();
        
		//get the NodeSet on the left
		Sequence leftSeq = getLeft().eval(contextSequence);
        if (!leftSeq.isPersistentSet())
            return genericCompare(leftSeq, contextSequence, null);

        NodeSet nodes = leftSeq.isEmpty() ? NodeSet.EMPTY_SET : (NodeSet)leftSeq;
		//nothing on the left, so nothing to do
		if(!(nodes instanceof VirtualNodeSet) && nodes.isEmpty()) {
			//Well, we might discuss this one ;-)
			hasUsedIndex= true;
            return Sequence.EMPTY_SEQUENCE;
		}

        //get the Sequence on the right
		Sequence rightSeq = getRight().eval(contextSequence);
		//nothing on the right, so nothing to do
		if(rightSeq.isEmpty()) {
			//Well, we might discuss this one ;-)
			hasUsedIndex= true;
            return Sequence.EMPTY_SEQUENCE;
		}
		
		//get the type of a possible index
		int indexType = nodes.getIndexType();
        
        //See if we have a range index defined on the nodes in this sequence
		//remember that Type.ITEM means... no index ;-)
	    if(indexType != Type.ITEM) {
	    	if (LOG.isTraceEnabled())
	    		LOG.trace("found an index of type: " + Type.getTypeName(indexType));

            boolean indexScan = false;
            boolean indexMixed = false;
            if (contextSequence != null) {
                IndexFlags iflags = checkForQNameIndex(idxflags, context, contextSequence, contextQName);
                boolean indexFound = false;
                if (!iflags.indexOnQName) {
                    // if contextQName != null and no index is defined on
                    // contextQName, we don't need to scan other QName indexes
                    // and can just use the generic range index
                    indexFound = contextQName != null;
                    // if there's a qname index on some collection, scan them as well
                    if (iflags.partialIndexOnQName) {
                        indexMixed = true;
                    } else {
                        // set contextQName to null so the index lookup below is not
                        // restricted to that QName
                        contextQName = null;
                    }
                }
                if (!indexFound && contextQName == null) {
                    // if there are some indexes defined on a qname,
                    // we need to check them all
                    if (iflags.hasIndexOnQNames)
                        indexScan = true;
                    // else use range index defined on path by default
                }
            } else
                return nodeSetCompare(nodes, contextSequence);

            //Get the documents from the node set
			final DocumentSet docs = nodes.getDocumentSet();
			
	        //Holds the result
    		NodeSet result = null;

			//Iterate through the right hand sequence
			for (SequenceIterator itRightSeq = rightSeq.iterate(); itRightSeq.hasNext();) {
				//Get the index key
				Item key = itRightSeq.nextItem().atomize();

	            //if key has truncation, convert it to string
	            if(truncation != Constants.TRUNC_NONE) {
	            	if (!Type.subTypeOf(key.getType(), Type.STRING)) {
	            		LOG.info("Truncated key. Converted from " + Type.getTypeName(key.getType()) + " to xs:string");
	            		//truncation is only possible on strings
	            		key = key.convertTo(Type.STRING);
	            	}
	            }
		        //else if key is not the same type as the index
                //TODO : use Type.isSubType() ??? -pb
		        else if (key.getType() != indexType) {
		        	//try to convert the key to the index type
	            	try	{
	            		key = key.convertTo(indexType);
					} catch(XPathException xpe)	{
	            		//TODO : rethrow the exception ? -pb

			        	//Could not convert the key to a suitable type for the index, fallback to nodeSetCompare()
		                if(context.getProfiler().isEnabled())
		                    context.getProfiler().message(this, Profiler.OPTIMIZATION_FLAGS, "OPTIMIZATION FALLBACK", "Falling back to nodeSetCompare (" + xpe.getMessage() + ")");

		                if (LOG.isTraceEnabled())
		                	LOG.trace("Cannot convert key: " + Type.getTypeName(key.getType()) + " to required index type: " + Type.getTypeName(indexType));

			            return nodeSetCompare(nodes, contextSequence);
					}
		        }

		        // If key implements org.exist.storage.Indexable, we can use the index
		        if (key instanceof Indexable) {
		        	if (LOG.isTraceEnabled())
		        		LOG.trace("Checking if range index can be used for key: " + key.getStringValue());

		        	if (Type.subTypeOf(key.getType(), indexType)) {
			        	if(truncation == Constants.TRUNC_NONE) {
			        		if (LOG.isTraceEnabled())
			        			LOG.trace("Using range index for key: " + key.getStringValue());

			        		//key without truncation, find key
		                    context.getProfiler().message(this, Profiler.OPTIMIZATIONS, "OPTIMIZATION", "Using value index '" + context.getBroker().getValueIndex().toString() +
		                    		"' to find key '" + Type.getTypeName(key.getType()) + "(" + key.getStringValue() + ")'");

                            NodeSet ns;
                            if (indexScan)
                                ns = context.getBroker().getValueIndex().findAll(relation, docs, nodes, NodeSet.ANCESTOR, (Indexable)key);
                            else {
                                ns = context.getBroker().getValueIndex().find(relation, docs, nodes, NodeSet.ANCESTOR, contextQName, (Indexable)key, indexMixed);
                            }
                            hasUsedIndex = true;

		                    if (result == null)
								result = ns;
							else
								result = result.union(ns);

		                } else {
				        	//key with truncation, match key
                            if (LOG.isTraceEnabled())
                                context.getProfiler().message(this, Profiler.OPTIMIZATIONS, "OPTIMIZATION", "Using value index '" + context.getBroker().getValueIndex().toString() +
		                    		"' to match key '" + Type.getTypeName(key.getType()) + "(" + key.getStringValue() + ")'");

                            if (LOG.isTraceEnabled())
			        			LOG.trace("Using range index for key: " + key.getStringValue());
                            
                            try {
                                NodeSet ns;
                                if (indexScan)
                                    ns = context.getBroker().getValueIndex().matchAll(docs, nodes, NodeSet.ANCESTOR,
                                        getRegexp(key.getStringValue()).toString(), DBBroker.MATCH_REGEXP, 0, true);
                                else
                                    ns = context.getBroker().getValueIndex().match(docs, nodes, NodeSet.ANCESTOR,
                                        getRegexp(key.getStringValue()).toString(), contextQName, DBBroker.MATCH_REGEXP);
								hasUsedIndex = true;

								if (result == null)
									result = ns;
								else
									result = result.union(ns);

							} catch (EXistException e) {
								throw new XPathException(this, e.getMessage(), e);
							}
						}
			        } else {
			        	//our key does is not of the correct type
		                if(context.getProfiler().isEnabled())
		                    context.getProfiler().message(this, Profiler.OPTIMIZATION_FLAGS, "OPTIMIZATION FALLBACK", "Falling back to nodeSetCompare (key is of type: " +
		                    		Type.getTypeName(key.getType()) + ") whereas index is of type '" + Type.getTypeName(indexType) + "'");

		                if (LOG.isTraceEnabled())
		                	LOG.trace("Cannot use range index: key is of type: " + Type.getTypeName(key.getType()) + ") whereas index is of type '" +
		                			Type.getTypeName(indexType));

		                return nodeSetCompare(nodes, contextSequence);
			        }		        	
		        } else {
		        	//our key does not implement org.exist.storage.Indexable
	                if(context.getProfiler().isEnabled())
	                    context.getProfiler().message(this, Profiler.OPTIMIZATION_FLAGS, "OPTIMIZATION FALLBACK", "Falling back to nodeSetCompare (key is not an indexable type: " +
	                    		key.getClass().getName());

	                if (LOG.isTraceEnabled())
	                	LOG.trace("Cannot use key which is of type '"  + key.getClass().getName());

	                return nodeSetCompare(nodes, contextSequence);

		        }
            }
            if (context.getProfiler().traceFunctions())
                context.getProfiler().traceIndexUsage(context, PerformanceStats.RANGE_IDX_TYPE, this,
                    PerformanceStats.BASIC_INDEX, System.currentTimeMillis() - start);
        	return result;
		} else {
	    	if (LOG.isTraceEnabled())
	    		LOG.trace("No suitable index found for key: " + rightSeq.getStringValue());

	    	//no range index defined on the nodes in this sequence, so fallback to nodeSetCompare
            if(context.getProfiler().isEnabled())
                context.getProfiler().message(this, Profiler.OPTIMIZATION_FLAGS, "OPTIMIZATION FALLBACK", "falling back to nodeSetCompare (no index available)");

            return nodeSetCompare(nodes, contextSequence);
		}
	}

    private CharSequence getRegexp(String expr) {
        switch (truncation) {
            case Constants.TRUNC_LEFT :
                return new StringBuilder().append(expr).append('$');
            case Constants.TRUNC_RIGHT :
                return new StringBuilder().append('^').append(expr);
            default :
                return expr;
        }
    }

    /**
	 * Cast the atomic operands into a comparable type
	 * and compare them.
	 */
	private boolean compareAtomic(Collator collator, AtomicValue lv, AtomicValue rv) throws XPathException {
		try {			
			int ltype = lv.getType();
			int rtype = rv.getType();
			if (ltype == Type.UNTYPED_ATOMIC) {
				//If one of the atomic values is an instance of xdt:untypedAtomic
				//and the other is an instance of a numeric type,
				//then the xdt:untypedAtomic value is cast to the type xs:double.
				if (Type.subTypeOf(rtype, Type.NUMBER)) {
				    //if(isEmptyString(lv))
				    //    return false;
					lv = lv.convertTo(Type.DOUBLE);
				//If one of the atomic values is an instance of xdt:untypedAtomic
				//and the other is an instance of xdt:untypedAtomic or xs:string,
				//then the xdt:untypedAtomic value (or values) is (are) cast to the type xs:string.
				} else if (rtype == Type.UNTYPED_ATOMIC || rtype == Type.STRING) {
					lv = lv.convertTo(Type.STRING);
					//if (rtype == Type.UNTYPED_ATOMIC)
						//rv = rv.convertTo(Type.STRING);
					//If one of the atomic values is an instance of xdt:untypedAtomic
					//and the other is not an instance of xs:string, xdt:untypedAtomic, or any numeric type,
					//then the xdt:untypedAtomic value is cast to the dynamic type of the other value.
				} else
					lv = lv.convertTo(rtype);
			} 
			if (rtype == Type.UNTYPED_ATOMIC) {
				//If one of the atomic values is an instance of xdt:untypedAtomic
				//and the other is an instance of a numeric type,
				//then the xdt:untypedAtomic value is cast to the type xs:double.
				if (Type.subTypeOf(ltype, Type.NUMBER)) {
				    //if(isEmptyString(lv))
				    //    return false;
					rv = rv.convertTo(Type.DOUBLE);
				//If one of the atomic values is an instance of xdt:untypedAtomic
				//and the other is an instance of xdt:untypedAtomic or xs:string,
				//then the xdt:untypedAtomic value (or values) is (are) cast to the type xs:string.
				} else if (ltype == Type.UNTYPED_ATOMIC || ltype == Type.STRING) {
					rv = rv.convertTo(Type.STRING);
					//if (ltype == Type.UNTYPED_ATOMIC)
					//	lv = lv.convertTo(Type.STRING);
				//If one of the atomic values is an instance of xdt:untypedAtomic
				//and the other is not an instance of xs:string, xdt:untypedAtomic, or any numeric type,
				//then the xdt:untypedAtomic value is cast to the dynamic type of the other value.
				} else
					rv = rv.convertTo(ltype);
			}
			/*
			if (backwardsCompatible) {
				if (!"".equals(lv.getStringValue()) && !"".equals(rv.getStringValue())) {
					// in XPath 1.0 compatible mode, if one of the operands is a number, cast
					// both operands to xs:double
					if (Type.subTypeOf(ltype, Type.NUMBER)
						|| Type.subTypeOf(rtype, Type.NUMBER)) {
							lv = lv.convertTo(Type.DOUBLE);
							rv = rv.convertTo(Type.DOUBLE);
					}
				}
			}
			*/
	        // if truncation is set, we always do a string comparison
	        if (truncation != Constants.TRUNC_NONE) {
	        	//TODO : log this ?
	            lv = lv.convertTo(Type.STRING);
	        }
//				System.out.println(
//					lv.getStringValue() + Constants.OPS[relation] + rv.getStringValue());
			switch(truncation) {
				case Constants.TRUNC_RIGHT:
					return lv.startsWith(collator, rv);
				case Constants.TRUNC_LEFT:
					return lv.endsWith(collator, rv);
				case Constants.TRUNC_BOTH:
					return lv.contains(collator, rv);
				default:
					return lv.compareTo(collator, relation, rv);
			}			
		} catch (XPathException e) {
            e.setLocation(e.getLine(), e.getColumn());
			throw e;
		}
	}

    /**
     * @param lv
     * @return Whether or not <code>lv</code> is an empty string
	 * @throws XPathException
     */
    private static boolean isEmptyString(AtomicValue lv) throws XPathException {
        if(Type.subTypeOf(lv.getType(), Type.STRING) || lv.getType() == Type.ATOMIC) {
            if(lv.getStringValue().length() == 0)
                return true;
        }
        return false;
    }

    public boolean hasUsedIndex() {
        return hasUsedIndex;
    }

    /* (non-Javadoc)
     * @see org.exist.xquery.PathExpr#dump(org.exist.xquery.util.ExpressionDumper)
     */
    public void dump(ExpressionDumper dumper) {
        if (truncation == Constants.TRUNC_BOTH) {
        	dumper.display("contains").display('(');
        	getLeft().dump(dumper);
        	dumper.display(", ");
        	getRight().dump(dumper);
        	dumper.display(")");
        } else {
        	getLeft().dump(dumper);
        	dumper.display(' ').display(Constants.OPS[relation]).display(' ');
        	getRight().dump(dumper);
        }
    }

    public String toString() {
    	StringBuilder result = new StringBuilder();    	
    	if (truncation == Constants.TRUNC_BOTH) {    		
    		result.append("contains").append('(');
    		result.append(getLeft().toString());
    		result.append(", ");
    		result.append(getRight().toString());
    		result.append(")");
    	} else {
    		result.append(getLeft().toString());
    		result.append(' ').append(Constants.OPS[relation]).append(' ');
    		result.append(getRight().toString());
    	}    	
    	return result.toString();
    }    
    
	protected void switchOperands() {
        context.getProfiler().message(this, Profiler.OPTIMIZATIONS,  "OPTIMIZATION", "Switching operands");
        //Invert relation
		switch (relation) {
			case Constants.GT :
				relation = Constants.LT;
				break;
			case Constants.LT :
				relation = Constants.GT;
				break;
			case Constants.LTEQ :
				relation = Constants.GTEQ;
				break;
			case Constants.GTEQ :
				relation = Constants.LTEQ;
				break;
			//What about Constants.EQ and Constants.NEQ ? Well, it seems to never be called 
		}
		Expression right = getRight();
		setRight(getLeft());
		setLeft(right);
	}

	/**
	 * Possibly switch operands to simplify execution
	 */
	protected void simplify() {        
		//Prefer nodes at the left hand
		if ((!Type.subTypeOf(getLeft().returnsType(), Type.NODE)) && 
              Type.subTypeOf(getRight().returnsType(), Type.NODE))
			switchOperands();
        //Prefer fewer items at the left hand
		else if ((Cardinality.checkCardinality(Cardinality.MANY, getLeft().getCardinality())) && 
                 !(Cardinality.checkCardinality(Cardinality.MANY, getRight().getCardinality())))
			switchOperands();
	}
	
	protected Collator getCollator(Sequence contextSequence) throws XPathException {
		if(collationArg == null)
			return context.getDefaultCollator();
		String collationURI = collationArg.eval(contextSequence).getStringValue();
		return context.getCollator(collationURI);
	}
	
	public void setCollation(Expression collationArg) {
		this.collationArg = collationArg;
	}

    public static IndexFlags checkForQNameIndex(IndexFlags idxflags, XQueryContext context, Sequence contextSequence, QName contextQName) {
        idxflags.reset(contextQName != null);
        for (Iterator i = contextSequence.getCollectionIterator(); i.hasNext(); ) {
            Collection collection = (Collection) i.next();
            if (collection.getURI().equalsInternal(XmldbURI.SYSTEM_COLLECTION_URI))
                continue;
            IndexSpec idxcfg = collection.getIndexConfiguration(context.getBroker());
            boolean hasIndex = contextQName != null && idxcfg.getIndexByQName(contextQName) != null;
            if (!idxflags.partialIndexOnQName && hasIndex)
                idxflags.partialIndexOnQName = true;
            if (idxflags.indexOnQName && !hasIndex) {
                idxflags.indexOnQName = false;
                if (LOG.isTraceEnabled())
                    LOG.trace("cannot use index on QName: " + contextQName + ". Collection " + collection.getURI() +
                        " does not define an index");
            }
            if (!idxflags.hasIndexOnQNames && idxcfg.hasIndexesByQName())
                idxflags.hasIndexOnQNames = true;
            if (!idxflags.hasIndexOnPaths && idxcfg.hasIndexesByPath())
                idxflags.hasIndexOnPaths = true;
        }
        return idxflags;
    }

    /* (non-Javadoc)
	 * @see org.exist.xquery.PathExpr#resetState()
	 */
	public void resetState(boolean postOptimization) {
		super.resetState(postOptimization);
		getLeft().resetState(postOptimization);
		getRight().resetState(postOptimization);
        if (!postOptimization) {
            cached = null;
            preselectResult = null;
            hasUsedIndex = false;
        }
    }

    public void accept(ExpressionVisitor visitor) {
        visitor.visitGeneralComparison(this);
    }

    public final static class IndexFlags {

        public boolean indexOnQName = true;
        public boolean partialIndexOnQName = false;
        public boolean hasIndexOnPaths = false;
        public boolean hasIndexOnQNames = false;

        public boolean indexOnQName() {
            return indexOnQName;
        }

        public boolean hasIndexOnPaths() {
            return hasIndexOnPaths;
        }

        public boolean hasIndexOnQNames() {
            return hasIndexOnQNames;
        }

        public void reset(boolean indexOnQName) {
            this.indexOnQName = indexOnQName;
            this.partialIndexOnQName = false;
            this.hasIndexOnPaths = false;
            this.hasIndexOnQNames = false;
        }
    }
}