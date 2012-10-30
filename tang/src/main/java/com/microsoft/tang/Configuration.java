package com.microsoft.tang;

import java.io.PrintStream;

/**
 * TANG Configuration object.
 * 
 * Tang Configuration objects are immutable and constructed via
 * ConfigurationBuilders.
 * 
 * @author sears
 */
public interface Configuration {

  /**
   * Writes this Configuration to the given OutputStream.
   * 
   * @param s
   * @throws IOException
   */
  public abstract void writeConfigurationFile(PrintStream s);

}