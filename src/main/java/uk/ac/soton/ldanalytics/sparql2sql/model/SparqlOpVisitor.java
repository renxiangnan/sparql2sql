package uk.ac.soton.ldanalytics.sparql2sql.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import uk.ac.soton.ldanalytics.sparql2sql.util.FormatUtil;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.sparql.algebra.OpVisitor;
import com.hp.hpl.jena.sparql.algebra.op.OpAssign;
import com.hp.hpl.jena.sparql.algebra.op.OpBGP;
import com.hp.hpl.jena.sparql.algebra.op.OpConditional;
import com.hp.hpl.jena.sparql.algebra.op.OpDatasetNames;
import com.hp.hpl.jena.sparql.algebra.op.OpDiff;
import com.hp.hpl.jena.sparql.algebra.op.OpDisjunction;
import com.hp.hpl.jena.sparql.algebra.op.OpDistinct;
import com.hp.hpl.jena.sparql.algebra.op.OpExt;
import com.hp.hpl.jena.sparql.algebra.op.OpExtend;
import com.hp.hpl.jena.sparql.algebra.op.OpFilter;
import com.hp.hpl.jena.sparql.algebra.op.OpGraph;
import com.hp.hpl.jena.sparql.algebra.op.OpGroup;
import com.hp.hpl.jena.sparql.algebra.op.OpJoin;
import com.hp.hpl.jena.sparql.algebra.op.OpLabel;
import com.hp.hpl.jena.sparql.algebra.op.OpLeftJoin;
import com.hp.hpl.jena.sparql.algebra.op.OpList;
import com.hp.hpl.jena.sparql.algebra.op.OpMinus;
import com.hp.hpl.jena.sparql.algebra.op.OpNull;
import com.hp.hpl.jena.sparql.algebra.op.OpOrder;
import com.hp.hpl.jena.sparql.algebra.op.OpPath;
import com.hp.hpl.jena.sparql.algebra.op.OpProcedure;
import com.hp.hpl.jena.sparql.algebra.op.OpProject;
import com.hp.hpl.jena.sparql.algebra.op.OpPropFunc;
import com.hp.hpl.jena.sparql.algebra.op.OpQuad;
import com.hp.hpl.jena.sparql.algebra.op.OpQuadBlock;
import com.hp.hpl.jena.sparql.algebra.op.OpQuadPattern;
import com.hp.hpl.jena.sparql.algebra.op.OpReduced;
import com.hp.hpl.jena.sparql.algebra.op.OpSequence;
import com.hp.hpl.jena.sparql.algebra.op.OpService;
import com.hp.hpl.jena.sparql.algebra.op.OpSlice;
import com.hp.hpl.jena.sparql.algebra.op.OpTable;
import com.hp.hpl.jena.sparql.algebra.op.OpTopN;
import com.hp.hpl.jena.sparql.algebra.op.OpTriple;
import com.hp.hpl.jena.sparql.algebra.op.OpUnion;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.core.VarExprList;
import com.hp.hpl.jena.sparql.expr.Expr;
import com.hp.hpl.jena.sparql.expr.ExprAggregator;
import com.hp.hpl.jena.sparql.expr.ExprWalker;

public class SparqlOpVisitor implements OpVisitor {
	
	RdfTableMapping mapping = null;
	List<Node> eliminated = new ArrayList<Node>();
	List<Resource> traversed = new ArrayList<Resource>();
	Set<SNode> blacklist = new HashSet<SNode>();
	List<SelectedNode> selectedNodes = new ArrayList<SelectedNode>();
	Map<String,String> varMapping = new HashMap<String,String>();
	Map<String,String> aliases = new HashMap<String,String>();
	Set<String> tableList = new HashSet<String>();
	List<String> previousSelects = new ArrayList<String>();
	Set<String> traversedNodes = new HashSet<String>();
	Set<Triple> traversedTriples = new HashSet<Triple>();
	
//	String previousSelect = "";
	String selectClause = "SELECT ";
//	String projections = "";
	String fromClause = "FROM ";
	String whereClause = "WHERE ";
	String groupClause = "GROUP BY ";	
	String havingClause = "HAVING ";
	
	Boolean bgpStarted = false;
	
	public SparqlOpVisitor() {

	}
	
	public void useMapping(RdfTableMapping mapping) {
		this.mapping = mapping;
	}

	public void visit(OpBGP bgp) {
		bgpStarted = true;
		for(Model model:mapping.getMapping()) {
			List<Triple> patterns = bgp.getPattern().getList();
//			for(Triple t:patterns) {
//				System.out.println("triple:"+t);
//				graphTraverse(t,model);
//			}
			for(Triple pattern:patterns) {
				if(!traversedTriples.contains(pattern))
					graphTraverse(patterns,pattern,model);
			}
		}
	}
	
	public void graphTraverse(List<Triple> patterns, Triple t, Model model) {
		Node predicate = t.getPredicate();
		Node object = t.getObject();
		Node subject = t.getSubject(); 
		for(Statement stmt:getStatements(subject,predicate,object,model)) {
			System.out.println("stmt:"+stmt+",t:"+t);
			graphTraverseR(patterns,t,model,stmt,"");
		}
	}
	
	public Boolean graphTraverseR(List<Triple> patterns, Triple t, Model model, Statement stmt, String fmt) {
		Set<String> results = new HashSet<String>();
		for(Triple currentT:patterns) {
			Node currentS = currentT.getSubject();
			Node currentP = currentT.getPredicate();
			Node currentO = currentT.getObject();
			Node o = t.getObject();
			Resource sO = stmt.getObject().isResource() ? stmt.getObject().asResource() : null;
			if(o.equals(currentS)) {
				if(sO!=null) {
					RDFNode nodeO = null;
					StmtIterator stmts = model.listStatements(sO, model.createProperty(currentP.getURI()), nodeO);
					while(stmts.hasNext()) {
						Statement sStmt = stmts.next();
						if(currentO.isURI()) {
							if(sStmt.getObject().isResource()) {
								if(!sStmt.getObject().asResource().getURI().equals(currentO.getURI())) {
//									System.out.println("hitfalse:"+sStmt+" t:"+currentT);
//									traversedTriples.add(currentT);
									return false;
								}
							}
						} else if(currentO.isVariable()) {
							String nodeStr = sStmt.getSubject().toString()+":"+currentP.toString()+":"+sStmt.getObject().toString();
							if(!traversedNodes.contains(nodeStr)) {
								traversedNodes.add(nodeStr);
								if(sStmt.getObject().isLiteral()) {
									System.out.println(fmt+"literal:"+sStmt.getObject()+" t:"+currentT);
								} else if(sStmt.getObject().isResource() || sStmt.getObject().isAnon()) {
									System.out.println(fmt+"s:"+sStmt+" t:"+currentT);
									Boolean subResult = graphTraverseR(patterns,currentT,model,sStmt,fmt+"\t");
									results.add(currentT.getPredicate()+":"+subResult);
									if(subResult==false)
										System.out.println("sfalse:"+currentT);
								}
							}
						}
					}
//					traversedTriples.add(currentT);
				}
				
//				for(Statement sStmt:getStatements(currentS,currentP,currentO,model)) {
//					System.out.println(fmt+"-"+sStmt+" o:"+o+" t:"+currentT);
//					if(sO.equals(sStmt.getSubject())) {
//						String nodeStr = sStmt.getSubject().toString()+":"+currentP.toString()+":"+sStmt.getObject().toString();
//						if(!traversedNodes.contains(nodeStr)) {
//							traversedNodes.add(nodeStr);
//							System.out.println(fmt+"s:"+sStmt);
//							graphTraverseR(patterns,currentT,model,sStmt,fmt+"\t");
//							
//						}
//					}
//				}
				
			}
			else if(o.equals(currentO) && !currentS.equals(t.getSubject())) {
				if(sO!=null) {
					Resource nodeS = null;
//					System.out.println("o:"+sO+":"+currentS);
					StmtIterator stmts = model.listStatements(nodeS, model.createProperty(currentP.getURI()), model.asRDFNode(sO.asNode()));
					while(stmts.hasNext()) {
						Statement sStmt = stmts.next();
						if(currentS.isURI()) {
							if(sStmt.getSubject().isResource()) {
								if(!sStmt.getSubject().asResource().getURI().equals(currentS.getURI())) {
//									traversedTriples.add(Triple.create(currentT.getSubject(), currentT.getPredicate(), currentT.getObject()));
									return false;
								}
							}
						} else if(currentS.isVariable()) {
							if(sStmt.getSubject().isResource() || sStmt.getSubject().isAnon()) {
								String nodeStr = sStmt.getSubject().toString()+":"+currentP.toString()+":"+sStmt.getObject().toString();
								if(!traversedNodes.contains(nodeStr)) {
									currentT = Triple.create(currentT.getObject(), currentT.getPredicate(), currentT.getSubject());
									sStmt = model.createStatement(sStmt.getObject().asResource(), sStmt.getPredicate(), model.asRDFNode(sStmt.getSubject().asNode()));
									traversedNodes.add(nodeStr);
									System.out.println(fmt+"o:"+sStmt+" t:"+currentT);
									Boolean subResult = graphTraverseR(patterns,currentT,model,sStmt,fmt+"\t");
									results.add(currentT.getPredicate()+":"+subResult);
									if(subResult==false)
										System.out.println("ofalse:"+currentT);
								}
							}
						}
					}
//					traversedTriples.add(currentT);
				}
			}
		}
		for(String resultStr:results) {
			System.out.println(fmt+"sample:"+resultStr);
			String[] parts = resultStr.split(":");
			if(parts.length>1) {
				if(parts[1].equals("false")) {
					if(!results.contains(parts[0]+":true")) {
						return false;
					}
				}
			}
		}
		System.out.println(fmt+"--");
		return true;
	}
//	public void visit(OpBGP bgp) {
//		bgpStarted = true;
//		//result is a list of table and its columns to select and the variables they are tied to
//		for(Model model:mapping.getMapping()) {
////			System.out.println("--------------------START MAPPING----------------------");
//			List<Triple> patterns = bgp.getPattern().getList();
//			for(Triple t:patterns) {
//				Node subject = t.getSubject(); 
////				if(!eliminated.contains(subject)) { //check if subject has been eliminated 
//					Node predicate = t.getPredicate();
//					Node object = t.getObject();
////					System.out.println("pattern:"+t);
//					for(Statement stmt:getStatements(subject,predicate,object,model)) {
//						checkSubject(t,patterns,model,stmt);
////						System.out.println(stmt);
//						//add statements if not eliminated
//						if(!blacklist.contains(stmt.getSubject())) {
//							System.out.println("addnode:"+stmt+":"+t);
//							SelectedNode node = new SelectedNode();
//							node.setStatement(stmt);
//							node.setBinding(t);
//							selectedNodes.add(node);
//						} 
//					}
////				}
//			}
//			
////			System.out.println("-----------");
//			for(SelectedNode n:selectedNodes) {
//				if(n.isLeafValue()) {
//					String modifier = "";
//					if(!whereClause.trim().equals("WHERE")) {
//						modifier = " AND ";
//					}
//					whereClause += modifier + n.getWherePart();
//				} else if(n.isLeafMap()) {
////					System.out.println(n.getVar() + ":" + n.getTable() + "." + n.getColumn());
//					varMapping.put(n.getVar(), n.getTable() + "." + n.getColumn());
//					tableList.add(n.getTable());
//				} else if(n.isObjectVar) {
//					varMapping.put(n.getVar(), "'" + n.getObjectUri() + "'");
//				}
//				if(n.isSubjectLeafMap()) {
////					System.out.println(n.getSubjectVar() + ":" + n.getSubjectTable() + "." + n.getSubjectColumn());
//					varMapping.put(n.getSubjectVar(), n.getSubjectTable() + "." + n.getSubjectColumn());
//					tableList.add(n.getSubjectTable());
//				} else if(n.isSubjectVar) {
//					varMapping.put(n.getSubjectVar(), "'" + n.getObjectUri() + "'");
//				}
//			}
//			
////			System.out.println("--------------------END MAPPING----------------------");
//			//clean up
//			blacklist.clear();
//			traversed.clear();
//			eliminated.clear();
//		}
//	}

	private List<Statement> getStatements(Node subject, Node predicate, Node object, Model model) {
		Resource s = subject.isBlank() ? model.asRDFNode(subject).asResource():null;
		Property p = predicate.isVariable() ? null : model.createProperty(predicate.getURI());
		RDFNode o = object.isBlank() ? model.asRDFNode(object) : null;

		StmtIterator stmts = model.listStatements(s, p, o);
		List<Statement> stmtList = new ArrayList<Statement>();
		while(stmts.hasNext()) {
			Boolean addStatement = true;
			Statement stmt = stmts.next();
//			System.out.println(subject);
			if(!subject.isVariable()) {
				if(!stmt.getSubject().isAnon()) {
					String uri = stmt.getSubject().getURI();
					if(uri.contains("{")) {
						addStatement = FormatUtil.compareUriPattern(subject.getURI(),uri);
					} else {
						if(!uri.equals(subject.getURI()))
							addStatement = false;
					}
				}
			}
			if(!object.isVariable()) {
				RDFNode stmtObj = stmt.getObject();
				if(object.isURI()) {
					if(!stmtObj.isResource()) {
						addStatement = false;
					}
					else if(!stmtObj.isAnon()) {
						String uri = stmtObj.asResource().getURI();
						if(uri.contains("{")) {
							addStatement = FormatUtil.compareUriPattern(object.getURI(),uri);
						} else {
							if(!uri.equals(object.getURI()))
								addStatement = false;
						}
					}
				}
			}
			if(addStatement)
				stmtList.add(stmt);
		}
		return stmtList;
	}

	private void checkSubject(Triple originalTriple, List<Triple> patterns, Model model, Statement stmt) {
		for(Triple t:patterns) {
			if(!t.matches(originalTriple)) {
				if(t.subjectMatches(originalTriple.getSubject())) {
					Node subject = stmt.getSubject().asNode();
					Node predicate = t.getPredicate();
					Node object = t.getObject();
					if(getStatements(subject,predicate,object,model).isEmpty()) {
						//eliminate
						eliminate(t,stmt, model, patterns);
//						System.out.println("no statement:"+subject+","+predicate+","+object);
//						blacklist.add(stmt.getSubject());
//						eliminated.add(t.getSubject());
						break;
					}
//					System.out.println("matches statement:"+t);
				} else if(t.objectMatches(originalTriple.getSubject())) {
					Node subject = t.getSubject();
					Node predicate = t.getPredicate();
					Node object = stmt.getSubject().asNode();
					if(getStatements(subject,predicate,object,model).isEmpty()) {
						//eliminate
						eliminate(t,stmt, model, patterns);
//						System.out.println("no statement:"+subject+","+predicate+","+object);
//						blacklist.add(stmt.getSubject());
//						eliminated.add(t.getSubject());
						break;
					}
				}
			}
		}
	}

	private void eliminate(Triple t, Statement stmt, Model model, List<Triple> patterns) {
		//check if its a branch and where the branch is
		Node subject = t.getSubject();
		Node predicate = t.getPredicate();
		Node object = t.getObject();
		List<SNode> validSubjects = new ArrayList<SNode>();
		for(Statement correctStmt:getStatements(subject,predicate,object,model)) {
//			System.out.println(correctStmt);
			validSubjects.add(new SNode(correctStmt.getSubject(),t.getSubject()));
		}
//		Boolean hasBranch = validSubjects.size() > 0;
//		if(hasBranch) { //calculate the common node where it branches
//			calculateBranchNode(stmt.getSubject(),validSubjects,model);
//		}
			
		//backward elimination recursion and mark forward elimination/blacklisting
		eliminateR(stmt.getSubject(),validSubjects,model, 0, stmt.getSubject(), patterns, t.getSubject(), new ArrayList<SNode>());
		
		//forward elimination
		//add subject to list and also all connected objects if not branch
	}
	
	private int eliminateR(Resource subject, List<SNode> validSubjects,
			Model model, int count, Resource parent, List<Triple> patterns, Node var, List<SNode> path) {
//		System.out.println("\t"+parent+"->"+subject);
		traversed.add(subject);
		
		if(validSubjects.contains(new SNode(subject,var))) {
			return count;
		}
		
		for(Triple t:patterns) {
			StmtIterator stmts = null;
			Node nextVar = null;
			if(t.subjectMatches(var)) {
				stmts = model.listStatements(subject,null,(RDFNode)null);
				nextVar = t.getObject();
			}
			else if(t.objectMatches(var)) {
				stmts = model.listStatements(null,null,subject);
				nextVar = t.getMatchSubject();
			}
			
			if(stmts!=null) {
				while(stmts.hasNext()) {
//					if(count==1) { //create new path for each branch out
						List<SNode> childPath = new ArrayList<SNode>();
//					}
					
					Statement stmt = stmts.next();
					Resource nextNode = null;
					if(t.subjectMatches(var)) {
						if(!stmt.getObject().isLiteral()) {
							nextNode = stmt.getObject().asResource();
						} else {
							continue;
						}
					} else if(t.objectMatches(var)) {
						nextNode = stmt.getSubject();
					}
		//			System.out.println(o.isLiteral() + ":" + o + ":" + subject + ":" + stmt.getPredicate());
					if(!traversed.contains(nextNode)) {
						int nextCount = count + 1;
		//				System.out.println(o);
						int tempResult = eliminateR(nextNode.asResource(),validSubjects,model,nextCount,subject,patterns,nextVar,childPath);
						
//						//on the wind down add the branch
						path.add(new SNode(nextNode,t.getSubject()));
//						if(path!=null) {
//							path.add(nextNode);
//						} else {
////							System.out.println("path null");
//							path = new ArrayList<Resource>();
//							path.add(nextNode);
//							path.add(subject);
//							eliminateNodes(path);
//							path.clear();
//							path = null;
//						}
						if(tempResult>0) {
							if(count==tempResult/2) {
//								for(Resource r:path) {
//									if(blacklist.contains(r)) {
//										blacklist.remove(r);
//									}
//								}
								//clear the path down the line
								path.clear();
								
//								childPath.clear();
//								System.out.println("\n\n"+nextNode);
							} else {
								return tempResult;
							}
						}
//						if(count==1) {
						path.addAll(childPath);
//							path.add(parent);
							eliminateNodes(path);
//						}
					}
				}
			}
		}
		
		return 0;
	}
	
	private void eliminateNodes(List<SNode> path) {		
		//eliminate nodes in list	
		Iterator<SelectedNode> i = selectedNodes.iterator();
		while (i.hasNext()) {
		   SelectedNode node = i.next(); // must be called before you can call i.remove()
		   if(path.contains(node.getSubject())) {
			   i.remove();
			}
		}
		
		//blaclist/forward elimination
		blacklist.addAll(path);
		for(SNode n:blacklist) {
			System.out.println("eliminate:"+n);
		}
//		System.out.println("-------------");
	}

//	private Resource calculateBranchNode(Resource invalidNode, List<Resource> validSubjects, Model model) {
//		
//		//trivial case: they are connected to each other directly
//		StmtIterator stmts = model.listStatements(invalidNode,null,(RDFNode)null);
//		while(stmts.hasNext()) {
//			Statement stmt = stmts.next();
//			if(validSubjects.contains(stmt.getObject().asResource())) {
//				validSubjects.remove(stmt.getObject().asResource());
//			}
//		}
//		stmts = model.listStatements(null,null,invalidNode);
//		while(stmts.hasNext()) {
//			Statement stmt = stmts.next();
//			if(validSubjects.contains(stmt.getSubject())) {
//				validSubjects.remove(stmt.getSubject());
//			}
//		}
//		if(validSubjects.isEmpty()) {
//			return invalidNode;
//		}
//		
//		//expand by one neighbour each time to find common node
//		stmts = model.listStatements(invalidNode,null,(RDFNode)null);
//		while(stmts.hasNext()) {
//			Statement stmt = stmts.next();
//			if(validSubjects.contains(stmt.getObject().asResource())) {
//				validSubjects.remove(stmt.getObject().asResource());
//			}
//		}
//		stmts = model.listStatements(null,null,invalidNode);
//		while(stmts.hasNext()) {
//			Statement stmt = stmts.next();
//			if(validSubjects.contains(stmt.getSubject())) {
//				validSubjects.remove(stmt.getSubject());
//			}
//		}
//		
//		return invalidNode;
//		
//	}

	public void visit(OpQuadPattern arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpQuadBlock arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpTriple arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpQuad arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpPath arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpTable arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpNull arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpProcedure arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpPropFunc arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpFilter filters) {
//		System.out.println("Filter");	
		
		for(Expr filter:filters.getExprs().getList()) {
			SparqlFilterExprVisitor v = new SparqlFilterExprVisitor();
			v.setMapping(varMapping);
			ExprWalker.walk(v,filter);
			v.finishVisit();
			String modifier = "";
			if(!v.getExpression().equals("")) {
				if(!whereClause.equals("WHERE ")) {
					modifier = " AND ";
				}
				whereClause += modifier + v.getExpression();
			} else if(!v.getHavingExpression().equals("")) {
				if(!havingClause.equals("HAVING ")) {
					modifier = " AND ";
				}
				havingClause += modifier + v.getHavingExpression();
			}
		}
		
//		System.out.println(whereClause);
	}

	public void visit(OpGraph arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpService arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpDatasetNames arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpLabel arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpAssign arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpExtend arg0) {
//		System.out.println("extend");
		VarExprList vars = arg0.getVarExprList();
		for(Var var:vars.getVars()) {
			String originalKey = vars.getExpr(var).getVarName();
			if(aliases.containsKey(originalKey)) {
				String val = aliases.remove(originalKey);
				aliases.put(var.getName(), val);
			} 
			if(varMapping.containsKey(originalKey)) {
				String val = varMapping.remove(originalKey);
				varMapping.put(var.getName(), val);
			}
		}
	}

	public void visit(OpJoin arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpLeftJoin arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpUnion arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpDiff arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpMinus arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpConditional arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpSequence arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpDisjunction arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpExt arg0) {
		
		
	}

	public void visit(OpList arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpOrder arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpProject arg0) {
		
		
		if(tableList.size()>1) {
//			System.out.println("joins required");
			Map<String, Set<String>> joinMap = new HashMap<String,Set<String>>(); 
			for(SelectedNode node:selectedNodes) {
				if(node.isLeafMap) {
					String var = node.getVar();
					Set<String> cols = joinMap.get(var);
					if(cols==null) {
						cols = new HashSet<String>();
					}
					cols.add(node.getTable()+"."+node.getColumn());
					joinMap.put(var, cols);
				}
				if(node.isSubjectLeafMap) {
					String var = node.getSubjectVar();
					Set<String> cols = joinMap.get(var);
					if(cols==null) {
						cols = new HashSet<String>();
					}
					cols.add(node.getSubjectTable()+"."+node.getSubjectColumn());
					joinMap.put(var, cols);
				}
			}
			
			String joinExpression = "";
			for(Entry<String,Set<String>> joinItem:joinMap.entrySet()) {
				if(joinItem.getValue().size()>1) {
					int count = 0;
					for(String column:joinItem.getValue()) {
						if(count++>0) {
							joinExpression += "=";
						}
						joinExpression += column;
					}
				}
			}
			if(!whereClause.trim().equals("WHERE"))
				whereClause += " AND ";
			whereClause += " " + joinExpression + " ";
		}
		selectedNodes.clear(); //clear the selected node list from any bgps below this projection
		
		int count = 0; //add from tables
		for(String table:tableList) {
			if(count++>0) {
				fromClause += " , ";
			}
			fromClause += table;
		}
		tableList.clear();
		
//		System.out.println("project");
//		if(!previousSelect.equals("")) {//previous projection
////			if(!whereClause.trim().equals("WHERE")) {
////				whereClause += " AND ";
////			}
////			whereClause += projections + " IN (" + previousSelect + ") ";
//			if(!fromClause.trim().equals("FROM")) {
//				fromClause += " , ";
//			}
//			fromClause += " (" + previousSelect + ") ";
//		}
		
		count=0;
		for(Var var:arg0.getVars()) {
			if(count++>0) {
				selectClause += " , ";
//				projections += " , ";
			}
			if(aliases.containsKey(var.getName())) {
				String colName = aliases.remove(var.getName());
				selectClause += colName + " AS " + var.getName();
//				projections += var.getName();
			} else if(varMapping.containsKey(var.getName())){
				String rdmsName = varMapping.remove(var.getName());
				selectClause += rdmsName;
				if(!rdmsName.equals(var.getName())) {
					selectClause += " AS " + var.getName();
				}
				varMapping.put(var.getName(), var.getName());
//				projections += var.getName();
			} else {
				selectClause += var.getName();
//				projections += var.getName();
//				count--;
			}
		}
//		System.out.println(selectClause);
//		previousSelect = formatSQL();
		if(bgpStarted==true) {
			previousSelects.add(formatSQL());
		} else {
			for(String sel:previousSelects) {
				if(!fromClause.trim().equals("FROM")) {
					fromClause += " , ";
				}
				fromClause += " (" + sel + ") ";
			}
			previousSelects.clear();
			previousSelects.add(formatSQL());
		}
		
		//clear clauses
		selectClause = "SELECT ";
		fromClause = "FROM ";
		whereClause = "WHERE ";
		groupClause = "GROUP BY ";
		havingClause = "HAVING ";
		
		bgpStarted = false;
	}

	public void visit(OpReduced arg0) {
		// TODO Auto-generated method stub
		 
	}

	public void visit(OpDistinct arg0) {
		String previousSelect = previousSelects.remove(previousSelects.size()-1);
		previousSelect = previousSelect.replaceFirst("SELECT", "SELECT DISTINCT");
		previousSelects.add(previousSelect);
	}

	public void visit(OpSlice arg0) {
		String previousSelect = previousSelects.remove(previousSelects.size()-1);
		previousSelect += "LIMIT " + arg0.getLength();
		previousSelects.add(previousSelect);
	}

	public void visit(OpGroup group) {
//		System.out.println("group");
		VarExprList vars = group.getGroupVars();
		Map<Var,Expr> exprMap = vars.getExprs();
		int count = 0;
		for(Var var:vars.getVars()) {
			Expr expr = exprMap.get(var);
			SparqlGroupExprVisitor v = new SparqlGroupExprVisitor();
			v.setMapping(varMapping);
			ExprWalker.walk(v, expr);
			if(count++>0) {
				groupClause += " , ";
			}
			if(!v.getExpression().equals("")) {
				groupClause += v.getExpression();
				varMapping.put(var.getName(), v.getExpression());
//				aliases.put(var.getName(), v.getExpression());
			} else {
				groupClause += FormatUtil.mapVar(var.getName(),varMapping);
			} 
		}
//		System.out.println("group:"+groupClause);
		for(ExprAggregator agg:group.getAggregators()) {
			SparqlGroupExprVisitor v = new SparqlGroupExprVisitor();
			v.setMapping(varMapping);
			ExprWalker.walk(v, agg);
			varMapping.put(v.getAggKey(),v.getAggVal());
//			aliases.put(v.getAggKey(),v.getAggVal());
		}
	}

	public void visit(OpTopN arg0) {
		
		
	}
	
	private String formatSQL() {		
		if(selectClause.trim().equals("SELECT")) {
			return "";
		} 
		if(fromClause.trim().equals("FROM")) {
			return "";
		}
		if(whereClause.trim().equals("WHERE")) {
			whereClause = "";
		}
		if(groupClause.trim().equals("GROUP BY")) {
			groupClause = "";
		}
		if(havingClause.trim().equals("HAVING")) {
			havingClause = "";
		}
		
		return selectClause + " " +
				fromClause + " " +
				whereClause + " " +
				groupClause + " " +
				havingClause + " ";
	}
	
	public String getSQL() {
		if(previousSelects.size()>0) {
			return previousSelects.get(0);
		}
		return null;
	}

}