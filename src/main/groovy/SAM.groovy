/*
 *  Groovy NGS Utils - Some simple utilites for processing Next Generation Sequencing data.
 *
 *  Copyright (C) 2013 Simon Sadedin, ssadedin<at>gmail.com
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.management.UnixOperatingSystemMXBean;

import groovy.transform.CompileStatic;
import net.sf.samtools.BAMRecord;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFileWriter;
import net.sf.samtools.SAMFileWriterFactory;
import net.sf.samtools.SAMReadGroupRecord;
import net.sf.samtools.SAMSequenceRecord;
import net.sf.samtools.SAMFileReader.ValidationStringency;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMRecordIterator;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

/**
 * An alternate alignment for a read. 
 * eg: constructed from the XA tag as output by bwa aln.
 * 
 * @author Simon
 */
class XA {
    String chr
    int pos
    String cigar
    int nm
    
    public String toString() {
        "[$chr:$pos $cigar NM=$nm]"
    }
}

class SAMRecordCategory {
   @CompileStatic
   static List<XA> getAlternateAlignments(BAMRecord record) {
       String xas = record.getAttribute("XA")
       if(!xas)
           return null
       return xas.split(';').collect { String xa ->
         String [] parts = xa.split(',')
         new XA( chr: parts[0], pos: parts[1].substring(1) as Integer, cigar:parts[2],  nm : parts[-1] as Integer)
       }
   }
}

/**
 * Adds various Groovy idioms and convenience features to the 
 * Picard SAMFileReader.
 * <p>
 * There are three major classes of functionality supported:
 * 
 * <li>Iterating through and filtering reads in various ways
 * <li>Generating pileups and calculating read depth / coverage
 * <li>Accessing meta data (read groups, sample information, etc)
 * 
 * For simple looping, the {@link #eachRead(Closure)} static method
 * can be used without creating a SAM object at all:
 * <pre> SAM.eachRead { SAMRecord r -> println r.readName } </pre>
 * (this will read from standard input). More sophisticated use requires the construction of a SAM object, 
 * which allows, for example, iteration of read pairs:
 * <pre> new SAM("test.bam").eachPair { r1, r2 -> assert r1.readName == r2.readName } </pre>
 * <p>
 * A region can be optionally passed to iterate over:
 * 
 * <pre> new SAM("test.bam").eachPair("chr1",1000,2000) { r1, r2 -> assert r1.readName == r2.readName }</pre>
 * 
 * Filtering a BAM file to create file containing a subset of reads is supported explicitly:
 * 
 * <pre>new SAM("test.bam").filter("out.bam") { it.mappingQuality > 30 }</pre>
 * 
 * Generating pileups is also straightforward:
 * <pre> new SAM("test.bam").pileup("chr1",1000,2000) { p -> 
 *     println "There are ${p.countOf('A')} A bases at position chr1:$p.position" 
 * }</pre>
 * See the {@link Pileup} class for more information about operations on pileups.
 * <p><br>
 * <i>Notes:</i>
 * <li>Most operations filter out reads with mapping quality 0 by default. To 
 * avoid this, set the minMappingQuality property on the SAM object.
 * <li>All operations with this class require the BAM file to be indexed.
 * <li>SAM and BAM files are treated transparently the same.
 * <p>
 * @author simon.sadedin@mcri.edu.au
 */
class SAM {
    
    SAMFileReader samFileReader;
    
    File samFile
    
    File indexFile
    
    int minMappingQuality = 1
    
    static boolean progress = false
    
    SAM(String fileName) {
        this(new File(fileName))
    }
    
    SAM(File file) {
        this.samFile = file
        if(!this.samFile.exists())
            throw new FileNotFoundException("BAM file could not be opened at ${samFile.absolutePath}")
        this.indexFile = new File(samFile.absolutePath + ".bai")
        if(!indexFile.exists()) {
            indexFile = new File(samFile.absolutePath.replaceAll(".bam\$",".bai"))
        }
        if(!indexFile.exists())
            throw new FileNotFoundException("Please ensure your BAM / SAM file is indexed. File $indexFile could not be found.")
        
        SAMFileReader.setDefaultValidationStringency(ValidationStringency.SILENT)
        this.samFileReader = newReader()
        
    }
    
    SAMFileReader newReader() {
        new SAMFileReader(samFile, indexFile, false)         
    }
    
    /**
     * Return a new SAMFileWriter configured with the same settings as this 
     * SAM. It is the caller's responsibility to close the writer.
     * 
     * @param outputFileName    Name of file to write to
     * @return SAMFileWriter
     */
    SAMFileWriter newWriter(String outputFileName) {
        SAMFileWriterFactory f = new SAMFileWriterFactory()
        SAMFileHeader header = this.samFileReader.fileHeader
        SAMFileWriter w = f.makeBAMWriter(header, false, new File(outputFileName))
        return w
    }
    
    /**
     * Return a new SAMFileWriter configured with the same settings as this 
     * SAM. It is the caller's responsibility to close the writer.
     * 
     * @param outputFileName    Name of file to write to
     * @return SAMFileWriter
     */
    def withWriter(String outputFileName, Closure c) {
        SAMFileWriterFactory f = new SAMFileWriterFactory()
        SAMFileHeader header = this.samFileReader.fileHeader
        SAMFileWriter w = f.makeBAMWriter(header, false, new File(outputFileName))
        try {
            return c(w)
        }
        finally {
            w.close()
        }
    }
    
    /**
     * Iterate over each record in the same file in the order they are in the file
     * @param c
     */
    void eachRecord(Closure c) {
        use(SAMRecordCategory) {
          Iterator i = samFileReader.iterator()
          try {
              while(i.hasNext()) {
                  c(i.next())
              }
          }
          finally {
              i.close()
          }
        }
    }
    
	/**
	 * Call the given closure for every pair of reads in a BAM file containing paired end reads.
	 * <p>
	 * Note: the algorithm works by keeping a running buffer of reads, and iterating through
	 * the reads in order until each single read finds its mate. This means that 
	 * reads having no mate accumulate in the buffer without ever being removed. Thus 
	 * a large BAM file containing millions of unpaired reads could cause this method to use
	 * substantial ammounts of memory.
	 * 
	 * @param c		Closure to call
	 */
    @CompileStatic
    void eachPair(Closure c) {
        Iterator<SAMRecord> iter = samFileReader.iterator()
        eachPair(iter,c)
    }
    
	/**
	 * Call the given closure for every pair of reads in a BAM file containing paired end reads.
	 * <p>
	 * Note: the algorithm works by keeping a running buffer of reads, and iterating through
	 * the reads in order until each single read finds its mate. This means that 
	 * reads having no mate accumulate in the buffer without ever being removed. Thus 
	 * a large BAM file containing millions of unpaired reads could cause this method to use
	 * substantial ammounts of memory.
	 * 
	 * @param iter	iterator to consumer reads from
	 * @param c		Closure to call
	 */
    @CompileStatic
    void eachPair(Iterator<SAMRecord> iter, Closure c) {
        SAMFileReader pairReader = newReader()
        Map<String,SAMRecord> buffer = new HashMap()
        ProgressCounter.withProgress { ProgressCounter progress ->
            try {
                while(iter.hasNext()) {
                    SAMRecord r1 = (SAMRecord)iter.next();
                    progress.count()
                    if(!r1.getReadPairedFlag() || r1.getReadUnmappedFlag())
                        continue
                        
                    if(buffer.containsKey(r1.readName)) {
                        c(r1,buffer[r1.readName])
                        buffer.remove(r1.readName)
                    }
                    else {
                        buffer[r1.readName] = r1
                    }
                }
                
                // Run down the buffer
//                buffer.each { String readName, r1 ->  
//                    c(r1, null)
//                }
            }
            finally {
                pairReader.close()
            }
        }
    }
    
    @CompileStatic
    void eachPair(String chr, int start, int end, Closure c) {
        Iterator<SAMRecord> iter = samFileReader.query(chr, start,end,false)
        eachPair(iter,c)
    }
    
    void eachRecord(int threads, Closure c) {
        List<SAMSequenceRecord> sequences = samFileReader.fileHeader.sequenceDictionary.sequences
        ExecutorService executor = Executors.newFixedThreadPool(threads)
        sequences.each { seq ->
          executor.execute {
              def reader = new SAMFileReader(samFile, indexFile, false)
              try {
                use(SAMRecordCategory) {
                  Iterator i = reader.query(seq.sequenceName, 1, seq.sequenceLength, false)
                  while(i.hasNext()) {
                      c(i.next())
                  }
                }
              }
              finally {
                  reader.close()
              }
          }
        }
        executor.shutdown()
    }
    
    List<SAMReadGroupRecord> getReadGroups() {
        samFileReader.getFileHeader().getReadGroups()
    }
    
    List<String> getSamples() {
        samFileReader.getFileHeader().getReadGroups()*.sample
    }
    
    void filter(Closure c) {
        filter("/dev/stdout",c)
    }
    
    @CompileStatic
    void filter(String outputFile, Closure c) {
        
        SAMFileReader reader = new SAMFileReader(samFile, indexFile, false)
        
        SAMFileWriterFactory f = new SAMFileWriterFactory()
        SAMFileHeader header = reader.fileHeader
        SAMFileWriter w = f.makeBAMWriter(header, false, new File(outputFile))
        SAMRecordIterator i = reader.iterator()
        int count = 0
        long lastPrintMs = System.currentTimeMillis()
        try {
          while(i.hasNext()) {
              SAMRecord r = (SAMRecord)i.next()
              if(c(r) == true) {
                  w.addAlignment(r)
              }
              if(count % 1000 == 0) {
                  if(System.currentTimeMillis() - lastPrintMs > 15000) {
                      System.err.println "${new Date()} Processed $count records"
                      lastPrintMs = System.currentTimeMillis()
                  }
              }
          }
        }
        finally {
            if(i != null)
                i.close()
                
            if(w)
                w.close()
        }
    }
    
    /**
     * Return the number of mapped reads overlapping the given position
     * 
     * @param chr   the sequence name / chromosome to query
     * @param pos   the chromosomal position to query
     * 
     * @return  the number of reads overlapping the position in the file
     */    
    @CompileStatic
    int coverage(String chr, int pos, Closure c=null) {
        return coverage(this.samFileReader, chr, pos, -1, c, minMappingQuality)
    }
    
    @CompileStatic
    int coverage(String chr, int pos, int end, Closure c=null) {
        return coverage(this.samFileReader, chr, pos, end, c, minMappingQuality)
    }
    
    @CompileStatic
    float meanCoverage(String chr, int pos, int end) {
        int total = 0
        this.pileup(chr, pos, end) { PileupIterator.Pileup p ->
            total += p.alignments.size()
        }
        return ((float)total)/ (end - pos + 1)
    }
    
    /**
     * Create a DescriptiveStatistics object
     */
    @CompileStatic
    CoverageStats coverageStatistics(String chr, int pos, int end) {
        CoverageStats stats = new CoverageStats(10000)
        int total = 0
        ProgressCounter progress = null
        if(this.progress)
            progress = new ProgressCounter(withTime:true, withRate:true)
            
        this.pileup(chr, pos, end) { PileupIterator.Pileup p ->
            stats.addValue(p.alignments.size())
            if(this.progress)
                progress.count()
        }
        stats
    }
    
    @CompileStatic
    CoverageStats coverageStatistics(Regions regions) {
        CoverageStats stats = new CoverageStats(10000)
        int total = 0
        ProgressCounter progress = null
        if(this.progress)
            progress = new ProgressCounter(withTime:true, withRate:true)

        // Flatten the regions down in case they overlap
        Regions flattenedRegions = regions.reduce()
        for(Region region in flattenedRegions) {
            this.pileup(region.chr, region.from, region.to) { PileupIterator.Pileup p ->
                stats.addValue(p.alignments.size())
                if(this.progress)
                    progress.count()
            }
        }
        stats
    }
    
      
    /**
     * Return the number of mapped reads overlapping the given position
     * 
     * @param r     the SAMFileReader (SAM / BAM file) containing reads
     * @param chr   the sequence name / chromosome to query
     * @param pos   the chromosomal position to query
     * 
     * @return  the number of reads overlapping the position in the file
     */
    @CompileStatic
    static int coverage(SAMFileReader r, String chr, int pos, int end=-1, Closure c=null, int minMappingQuality=1) {
        if(end == -1)
            end = pos
            
        ProgressCounter counter = new ProgressCounter()
        SAMRecordIterator i = r.query(chr, pos, end, false);
        try {
            int count = 0;
            while(i.hasNext()) {
                SAMRecord rec = (SAMRecord)i.next();
                if(progress)
                    counter.count()
                if(rec.getMappingQuality() < minMappingQuality) {
                    continue;
                }
                if(c == null || c(rec))
                  ++count;
            }
            return count;
        }
        finally {
            i.close();
        }
    }
    
    PileupIterator.Pileup pileup(String chr, int pos) {
        return pileup(chr,pos,pos).next()
    }
    
    @CompileStatic
    void pileup(String chr, int start, int end, Closure c) {
        PileupIterator i = pileup(chr,start,end)
        try {
          while(i.hasNext()) {
              c(i.next())
          }
        }
        finally {
            i.close()
        }
    }
    
    @CompileStatic
    PileupIterator pileup(String chr, int start, int end) {
        PileupIterator p = new PileupIterator(new SAMFileReader(samFile, indexFile, false), chr,start,end);
        p.setMinMappingQuality(this.minMappingQuality)
        return p
    }
    
	/**
	 * Close the underlying SAMFileReader
	 */
    void close() {
        if(this.samFileReader != null)
            this.samFileReader.close()
    }
    
	/**
	 * Read a BAM or SAM file from standard input and call the given closure for
	 * each read contained therein.
	 * @param c
	 */
    @CompileStatic
    static void eachRead(Closure c) {
        SAMFileReader reader = new SAMFileReader(System.in)
        Iterator<SAMRecord> iter = reader.iterator()
        while(iter.hasNext()) {
            c(iter.next())
        }
    }
    
    /**
     * Count the total number of reads in the SAM file
     * 
     * @return total number of reads (not pairs, each pair is counted as 2 reads)
     */
    @CompileStatic
    int size() {
        int count = 0
        eachRecord { SAMRecord r ->
            ++count
        }
        return count
    }
    
    public static long getOpenFileDescriptorCount() {
        OperatingSystemMXBean osStats = ManagementFactory.getOperatingSystemMXBean();
        if(osStats instanceof UnixOperatingSystemMXBean) {
           return ((UnixOperatingSystemMXBean)osStats).getOpenFileDescriptorCount();
        }
        return 0;
    }
}
