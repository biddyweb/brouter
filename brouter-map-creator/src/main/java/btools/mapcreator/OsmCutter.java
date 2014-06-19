/**
 * This program
 * - reads an *.osm from stdin
 * - writes 45*30 degree node tiles + a way file + a rel file
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import btools.expressions.BExpressionContext;
import btools.expressions.BExpressionMetaData;

public class OsmCutter extends MapCreatorBase
{
  private long recordCnt;
  private long nodesParsed;
  private long waysParsed;
  private long relsParsed;
  private long changesetsParsed;

  private DataOutputStream wayDos;
  private DataOutputStream cyclewayDos;

  public static void main(String[] args) throws Exception
  {
    System.out.println("*** OsmCutter: cut an osm map in node-tiles + a way file");
    if (args.length != 5 && args.length != 6)
    {
      System.out.println("usage: bzip2 -dc <map> | java OsmCutter <lookup-file> <out-tile-dir> <out-way-file> <out-rel-file> <filter-profile>");
      System.out.println("or   : java OsmCutter <lookup-file> <out-tile-dir> <out-way-file> <out-rel-file> <filter-profile> <inputfile> ");
      return;
    }

    new OsmCutter().process(
                   new File( args[0] )
                 , new File( args[1] )
                 , new File( args[2] )
                 , new File( args[3] )
                 , new File( args[4] )
                 , args.length > 5 ? new File( args[5] ) : null
                		 );
  }

  private BExpressionContext _expctxWay;
  private BExpressionContext _expctxNode;

  private BExpressionContext _expctxWayStat;
  private BExpressionContext _expctxNodeStat;

  public void process (File lookupFile, File outTileDir, File wayFile, File relFile, File profileFile, File mapFile ) throws Exception
  {
    if ( !lookupFile.exists() )
    {
      throw new IllegalArgumentException( "lookup-file: " +  lookupFile + " does not exist" );
    }

    BExpressionMetaData meta = new BExpressionMetaData();

    _expctxWay = new BExpressionContext("way", meta );
    _expctxNode = new BExpressionContext("node", meta );
    meta.readMetaData( lookupFile );
    _expctxWay.parseFile( profileFile, "global" );

    
   _expctxWayStat = new BExpressionContext("way", null );
   _expctxNodeStat = new BExpressionContext("node", null );

    this.outTileDir = outTileDir;
    if ( !outTileDir.isDirectory() ) throw new RuntimeException( "out tile directory " + outTileDir + " does not exist" );

    wayDos = new DataOutputStream( new BufferedOutputStream( new FileOutputStream( wayFile ) ) );
    cyclewayDos = new DataOutputStream( new BufferedOutputStream( new FileOutputStream( relFile ) ) );

    // read the osm map into memory
    long t0 = System.currentTimeMillis();
    new OsmParser().readMap( mapFile, this, this, this );
    long t1 = System.currentTimeMillis();
    
    System.out.println( "parsing time (ms) =" + (t1-t0) );

    // close all files
    closeTileOutStreams();
    wayDos.close();
    cyclewayDos.close();

    System.out.println( "-------- way-statistics -------- " );
    _expctxWayStat.dumpStatistics();
    System.out.println( "-------- node-statistics -------- " );
    _expctxNodeStat.dumpStatistics();

    System.out.println( statsLine() );
  }

  private void checkStats()
  {
    if ( (++recordCnt % 100000) == 0 ) System.out.println( statsLine() );
  }

  private String statsLine()
  {
    return "records read: " + recordCnt + " nodes=" + nodesParsed + " ways=" + waysParsed + " rels=" + relsParsed + " changesets=" + changesetsParsed;
  }


  @Override
  public void nextNode( NodeData n ) throws Exception
  {
    nodesParsed++;
    checkStats();

    if ( n.getTagsOrNull() != null )
    {
      int[] lookupData = _expctxNode.createNewLookupData();
      for( String key : n.getTagsOrNull().keySet() )
      {
        String value = n.getTag( key );
        _expctxNode.addLookupValue( key, value, lookupData );
        _expctxNodeStat.addLookupValue( key, value, null );
      }
      n.description = _expctxNode.encode(lookupData);
    }
    // write node to file
    int tileIndex = getTileIndex( n.ilon, n.ilat );
    if ( tileIndex >= 0 )
    {
      n.writeTo( getOutStreamForTile( tileIndex ) );
    }
  }


  @Override
  public void nextWay( WayData w ) throws Exception
  {
    waysParsed++;
    checkStats();

    // encode tags
    if ( w.getTagsOrNull() == null ) return;

    int[] lookupData = _expctxWay.createNewLookupData();
    for( String key : w.getTagsOrNull().keySet() )
    {
      String value = w.getTag( key );
      _expctxWay.addLookupValue( key, value, lookupData );
      _expctxWayStat.addLookupValue( key, value, null );
    }
    w.description = _expctxWay.encode(lookupData);
    
    if ( w.description == null ) return;

    // filter according to profile
    _expctxWay.evaluate( false, w.description, null );
    boolean ok = _expctxWay.getCostfactor() < 10000.; 
    _expctxWay.evaluate( true, w.description, null );
    ok |= _expctxWay.getCostfactor() < 10000.;
    if ( !ok ) return;
    
    w.writeTo( wayDos );
  }

  @Override
  public void nextRelation( RelationData r ) throws Exception
  {
    relsParsed++;
    checkStats();

    String route = r.getTag( "route" );
    // filter out non-cycle relations
    if ( route == null )
    {
      return;
    }

    String network =  r.getTag( "network" );
    if ( network == null ) network = "";
    writeId( cyclewayDos, r.rid );
    cyclewayDos.writeUTF( route );
    cyclewayDos.writeUTF( network );
    for ( int i=0; i<r.ways.size();i++ )
    {
      long wid = r.ways.get(i);
      writeId( cyclewayDos, wid );
    }
    writeId( cyclewayDos, -1 );
  }


  private int getTileIndex( int ilon, int ilat )
  {
     int lon = ilon / 45000000;
     int lat = ilat / 30000000;
     if ( lon < 0 || lon > 7 || lat < 0 || lat > 5 )
     {
       System.out.println( "warning: ignoring illegal pos: " + ilon + "," + ilat );
       return -1;
     }
     return lon*6 + lat;
  }

  protected String getNameForTile( int tileIndex )
  {
    int lon = (tileIndex / 6 ) * 45 - 180;
    int lat = (tileIndex % 6 ) * 30 - 90;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    return slon + "_" + slat + ".tls";
  }
}
