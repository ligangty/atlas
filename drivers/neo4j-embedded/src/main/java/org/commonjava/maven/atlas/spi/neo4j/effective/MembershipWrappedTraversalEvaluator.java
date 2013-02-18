package org.commonjava.maven.atlas.spi.neo4j.effective;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.graph.effective.rel.ProjectRelationship;
import org.apache.maven.graph.effective.traverse.ProjectNetTraversal;
import org.commonjava.maven.atlas.spi.neo4j.io.Conversions;
import org.commonjava.util.logging.Logger;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PathExpander;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.traversal.BranchState;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;

public class MembershipWrappedTraversalEvaluator<STATE>
    implements Evaluator, PathExpander<STATE>
{

    private final Logger logger = new Logger( getClass() );

    private final AbstractNeo4JEGraphDriver driver;

    private final ProjectNetTraversal traversal;

    private final Set<Long> seenRels = new HashSet<Long>();

    private final Set<Long> accepted = new HashSet<Long>();

    private final Set<Long> rejected = new HashSet<Long>();

    private final int pass;

    private boolean reversedExpander;

    private int expHits = 0;

    private int evalHits = 0;

    private int expMemberMisses = 0;

    private int evalMemberMisses = 0;

    private int expMemberHits = 0;

    private int evalMemberHits = 0;

    private int evalDupes = 0;

    private int expPreChecks = 0;

    private int evalPreChecks = 0;

    public MembershipWrappedTraversalEvaluator( final AbstractNeo4JEGraphDriver driver,
                                                final ProjectNetTraversal traversal, final int pass )
    {
        this.driver = driver;
        this.traversal = traversal;
        this.pass = pass;
    }

    private MembershipWrappedTraversalEvaluator( final MembershipWrappedTraversalEvaluator<STATE> ev,
                                                 final boolean reversedExpander )
    {
        this.driver = ev.driver;
        this.traversal = ev.traversal;
        this.pass = ev.pass;
        this.reversedExpander = reversedExpander;
    }

    public void printStats()
    {
        logger.info( "\n\n\n\nStats for traversal:\n" + "---------------------\n" + "\ntotal expander hits: %d"
                         + "\nexpander membership hits: %d" + "\nexpander membership misses: %d"
                         + "\nexpander preCheck() calls: %d" + "\n\ntotal evaluator hits: %d"
                         + "\nevaluator membership hits: %d" + "\nevaluator membership misses: %s"
                         + "\nevaluator duplicate hits: %d" + "\nevaluator preCheck() calls: %d\n\n\n\n", expHits,
                     expMemberHits,
                     expMemberMisses, expPreChecks, evalHits, evalMemberHits, evalMemberMisses, evalDupes,
                     evalPreChecks );
    }

    public Evaluation evaluate( final Path path )
    {
        evalHits++;

        final Relationship rel = path.lastRelationship();
        if ( rel == null )
        {
            return Evaluation.EXCLUDE_AND_CONTINUE;
        }

        if ( seenRels.contains( rel.getId() ) )
        {
            evalDupes++;
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

        seenRels.add( rel.getId() );

        if ( accepted.contains( rel.getId() ) )
        {
            evalMemberHits++;
            evalPreChecks++;
            return Evaluation.INCLUDE_AND_CONTINUE;
        }
        else if ( rejected.contains( rel.getId() ) )
        {
            evalMemberHits++;
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

        final Node node = path.endNode();

        if ( driver.inMembership( node ) && driver.inMembership( rel ) )
        {
            evalMemberHits++;

            final ProjectRelationship<?> lastRel = Conversions.toProjectRelationship( rel );

            final List<ProjectRelationship<?>> relPath = driver.convertToRelationships( path.relationships() );
            if ( relPath.indexOf( lastRel ) == relPath.size() - 1 )
            {
                logger.warn( "\n\n\n\n\nREMOVING last-relationship: %s from path!\n\n\n\n\n" );
                relPath.remove( relPath.size() - 1 );
            }

            if ( relPath.isEmpty() )
            {
                return Evaluation.EXCLUDE_AND_CONTINUE;
            }
            else
            {
                if ( traversal.preCheck( lastRel, relPath, pass ) )
                {
                    accepted.add( rel.getId() );
                    evalPreChecks++;

                    return Evaluation.INCLUDE_AND_CONTINUE;
                }
                else
                {
                    rejected.add( rel.getId() );
                }
            }
        }
        else
        {
            evalMemberMisses++;
        }

        return Evaluation.EXCLUDE_AND_PRUNE;
    }

    public Iterable<Relationship> expand( final Path path, final BranchState<STATE> state )
    {
        expHits++;

        final Node node = path.endNode();
        //        logger.info( "START expansion for node: %s", node );

        // TODO: Is node(0) appropriate to see??
        if ( node.getId() != 0 && !driver.inMembership( node ) )
        {
            expMemberMisses++;
            //            logger.info( "%s not in membership. Skipping expansion.", node );
            return Collections.emptySet();
        }

        final Relationship rel = path.lastRelationship();
        if ( rel != null )
        {
            if ( !driver.inMembership( rel ) )
            {
                //                logger.info( "Last relationship (%s) is not in membership.", rel );
                expMemberMisses++;
                return Collections.emptySet();
            }
        }

        expMemberHits++;

        final Iterable<Relationship> rs =
            node.getRelationships( reversedExpander ? Direction.INCOMING : Direction.OUTGOING );
        if ( rs == null )
        {
            //            logger.info( "No relationships from end-node: %s", node );
            return Collections.emptySet();
        }

        final Set<Relationship> result = new HashSet<Relationship>();
        List<ProjectRelationship<?>> rels = null;
        for ( final Relationship r : rs )
        {
            if ( accepted.contains( r.getId() ) )
            {
                result.add( r );
                continue;
            }
            else if ( rejected.contains( r.getId() ) )
            {
                continue;
            }

            //            logger.info( "Pre-checking relationship %s for expansion using filter: %s", r, traversal );
            final ProjectRelationship<?> projectRel = Conversions.toProjectRelationship( r );
            if ( rels == null )
            {
                rels = getPathRelationships( path );
            }

            if ( traversal.preCheck( projectRel, rels, pass ) )
            {
                accepted.add( r.getId() );
                expPreChecks++;
                //                logger.info( "Adding for expansion: %s", r );
                result.add( r );
            }
            else
            {
                rejected.add( r.getId() );
            }
        }

        //        logger.info( "Expanding for %d relationships.", result.size() );
        return result;
    }

    private List<ProjectRelationship<?>> getPathRelationships( final Path path )
    {
        List<ProjectRelationship<?>> rels;
        final Iterable<Relationship> rs = path.relationships();
        if ( rs == null )
        {
            //            logger.info( "Constructing empty relationship list for filter." );
            rels = Collections.emptyList();
        }
        else
        {
            rels = driver.convertToRelationships( rs );
            //            logger.info( "Got relationship list %d entries long for filter", rels.size() );
        }

        if ( reversedExpander )
        {
            //            logger.info( "Reversing relationship list for filter." );
            Collections.reverse( rels );
        }

        return rels;
    }

    public PathExpander<STATE> reverse()
    {
        return new MembershipWrappedTraversalEvaluator<STATE>( this, true );
    }

}