package water.api.rest.schemas;

import water.api.Request.API;
import water.api.rest.REST;
import water.api.rest.REST.Versioned;
import water.api.rest.Version;
import water.api.rest.schemas.ApiSchema;

public class CloudStatusSchemaV1 extends ApiSchema<Version.V1> {
  @API(help="version")
  public String version;

  @API(help="cloud_name")
  public String cloud_name;

  @API(help="node_name")
  public String node_name;

  @API(help="cloud_size")
  public int cloud_size;

  @API(help="cloud_uptime_millis")
  public long cloud_uptime_millis;

  @API(help="cloud_healthy")
  public boolean cloud_healthy;

  @API(help="consensus")
  public boolean consensus;

  @API(help="locked")
  public boolean locked;

  @API(help="nodes")
  public NodeStatusSchemaV1[] nodes;

  public Version.V1 getVersion() { return Version.v1; }

}