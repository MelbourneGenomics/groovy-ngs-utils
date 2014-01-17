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

import java.util.Iterator;

import groovy.lang.IntRange;
import groovy.transform.CompileStatic;

/**
 * Indexes a set of ranges so that the ranges crossing any point
 * can be identified quickly and easily.
 * <p>
 * The RangeIndex models ranges by their breakpoints. Each time a new range
 * is inserted, the breakpoints are indexed and the list of overlapping ranges
 * at each breakpoint is tracked for each breakpoint entry. This makes it 
 * easy to find overlapping ranges at any point.
 * 
 * @author simon.sadedin@mcri.edu.au
 */
class RangeIndex implements Iterable<IntRange> {
    
    /**
     * The actual index - a map from position to the list of ranges
     * covering the region after the position. There is an entry in this
     * index for each position where a range starts or ends.
     */
    TreeMap<Integer,List<IntRange>> ranges = new TreeMap()
    
    void add(int startPosition, int endPosition, Object extra = null) {
        add(extra != null ? new GRange(startPosition, endPosition-1, extra) : new IntRange(startPosition, endPosition-1))
    }
        
//    @CompileStatic
    void add(IntRange newRange) {
        
        // Any existing range over start position?
        int startPosition = (int)newRange.from
        int endPosition = (int)newRange.to
        Map.Entry<Integer,List<IntRange>> lowerEntry = ranges.lowerEntry(startPosition+1)
//        println "Adding range starting at $startPosition"
        
        // Inserting a range at the lowest end is relatively straightforward, so 
        // handle it separately
        if(lowerEntry == null || lowerEntry.value.isEmpty()) { 
            addLowestRange(newRange)
            return
        }
        
        // Already a range preceding this position: we to check for overlap and maybe split it
        if(lowerEntry.value == null) {
            println "NULL VALUE"
        }
        else
        if(lowerEntry.value[0] == null) {
            println "VALUE HAS NULL CONTENTS"
        }
            
        if(ranges.containsKey(startPosition)) { // If starts at exactly same position, share its entry
            ranges[startPosition].add(newRange)
            checkRanges(startPosition)
        }
        else { // starts at a new position, have to find overlapping ranges and split them
                
            // Add all the overlapping regions from the adjacent lower region to
            // our new breakpoint
            List<IntRange> lowerSplitRegion =  lowerEntry.value.grep { it.to > startPosition }
                
            // Add our new range as covered by the split part
            lowerSplitRegion.add(newRange)
            ranges[startPosition] = lowerSplitRegion
            checkRanges(startPosition)
        }
                
        Map.Entry containedEntry = ranges.higherEntry(startPosition)
        List<Integer> rangesToAddTo = []
        while(containedEntry && containedEntry.key < endPosition) {
                
            Map.Entry higherEntry = ranges.higherEntry(containedEntry.key)   
                
            // Note: boundaryPoint is -1 when the ranged overlapped is an "ending" range - one at the 
            // border of a gap with no overlapping ranges. In that case we need to add ourselves to the 
            // boundary breakpoint and break
            int boundaryPoint = (higherEntry!=null) ? higherEntry.key : ((Integer)containedEntry.value[0]?.to?:-1)
                
            // If existing range is entirely contained within the one we are adding
            // then just add our new range to the list of ranges covered
            if(endPosition > boundaryPoint) { // Entirely contained
                    
                // If there's no higher entry at all, then we can just add a new boundary
                // and a region with only our range and break
                if(higherEntry == null && boundaryPoint>=0) {
                    ranges[boundaryPoint] = [newRange]
                    checkRanges(boundaryPoint)
                }
            }
            else { // The start is contained, but the end is not : split the upper range
                // Make a new range from the end of our range to the start of the next higher range
                // It needs to have the previous range only, not our new range
                // NOTE: list.clone() causes static compilation to fail with verify error
                List<IntRange> clonedList = containedEntry.value.grep { endPosition < it.to }
//                clonedList.addAll(containedEntry.value)
                ranges[endPosition+1] = clonedList
                checkRanges(endPosition+1)
            }
            assert containedEntry.key < endPosition
            
            rangesToAddTo << containedEntry.key
                
            containedEntry = higherEntry
        }
        rangesToAddTo.each { int startPos -> 
            ranges[startPos] << newRange 
            checkRanges(startPos)
        }
    }
    
//    @CompileStatic
    private void addLowestRange(IntRange newRange) {
        
        int startPosition = newRange.from
        int endPosition = newRange.to
        
        ranges.put(startPosition,[newRange])
        checkRanges(startPosition)
        
        // If there are any ranges that start before the end of this new range,
        // add a breakpoint, and add this range to the intervening ranges
        int entryPos = startPosition
        Map.Entry<Integer, List<IntRange>> higherEntry = ranges.higherEntry(startPosition)
        Map.Entry<Integer, List<IntRange>> lastEntry = null
        while(higherEntry && higherEntry.key < endPosition) {
            higherEntry.value.add(newRange)
            checkRanges(higherEntry.key)
            lastEntry = higherEntry
            higherEntry = ranges.higherEntry(higherEntry.key)
        }
        
        // The last region needs to be split into two
        if(lastEntry) {
            if(!ranges.containsKey(endPosition+1)) {
              List newRanges = lastEntry.value.grep { endPosition+1 in it }
              ranges[endPosition+1] = newRanges
              checkRanges(endPosition+1)
            }
        }
        else {
            // Need to mark the ending of the range so that future adds will
            // look up the right point for adding new overlapping ranges
            if(!ranges.containsKey(endPosition+1))
              ranges[endPosition+1] = []
        }
    }
    
    @CompileStatic
    void checkRanges(int position) {
        if(ranges[position] == null)
            return 
           
        ranges[position].each { IntRange r -> assert r.from <= position && r.to>=position : "Range $r.from - $r.to is added incorrectly to position $position" }
    }
    
    /**
     * Remove an existing range from the index. The existing range
     * MUST already be in the index, and cannot be a user-supplied range. 
     */
    void remove(Range r) {
        // There should be an entry where this range starts
        int lastPos = r.from-1
        List toRemove = []
        // Remove it from each breakpoint until we hit the end
        while(lastPos < r.to) {
          Map.Entry startEntry = this.ranges.higherEntry(lastPos)
          if(startEntry && startEntry.key > r.to)
              break
          
          if(!startEntry && lastPos == r.from-1)
              throw new IllegalArgumentException("Range $r.from-$r.to was not indexed, so cannot be removed")
          
          if(!startEntry)
              break
              
          List<IntRange> breakPointRanges = startEntry.value
//          println "Ranges are $breakPointRanges"
          // note issue when a new range starts at the same place as the range to be removed ends:
          // the new range probably is supposed to have the old range in its list? but it doesn't
          // so the last clause below stops us throwing on that case
          if(!breakPointRanges.isEmpty() && (!breakPointRanges.removeAll { it.from == r.from && it.to == r.to } && startEntry.key<r.to))
              throw new IllegalArgumentException("Range $r.from-$r.to is not present in the index, so cannot be removed")
          
          if(breakPointRanges.isEmpty()) {
              toRemove.add(startEntry.key)
          }
          lastPos = startEntry.key
        }
        
        toRemove.each { this.ranges.remove(it) }
        if(this.ranges.containsKey(r.to+1) && this.ranges[r.to+1].isEmpty()) {
            this.ranges.remove(r.to+1)
        }
    }
    
    List<Range> startingAt(int pos) {
        
        List<IntRange> result = this.ranges[pos]
        if(!result)
            return []
        
        // The given ranges could be starting at OR ending at the given position
        result.grep { it.from == pos }
    }
    
    List<Range> endingAt(int pos) {
        Map.Entry entry = this.ranges.lowerEntry(pos)
        if(!entry)
            return []
        List<IntRange> result = entry.value
        if(!result)
            return []
         result.grep { it.to == pos }
    }
    
    /**
     * Support for 'in' operator
     */
    boolean isCase(int position) {
        Map.Entry lowerEntry = ranges.lowerEntry(position+1)
        if(!lowerEntry)
            return false
        return lowerEntry.value.any { it.containsWithinBounds(position) }    
    }
    
    List<Range> getOverlaps(int position) {
        Map.Entry lowerEntry = ranges.lowerEntry(position+1)
        if(!lowerEntry)
            return []
        return lowerEntry.value.grep { it.containsWithinBounds(position) }
    }
    
    List<Range> getOverlaps(int start, int end) {
        IntRange interval = start..end-1
        List<Range> result = []
        Map.Entry entry = ranges.lowerEntry(start+1)
        if(!entry) 
            entry = ranges.higherEntry(start)
            
        // Iterate as long as we are in the range
        while(entry != null && entry.key <= end) {
            for(Range r in entry.value) {
               if(r.from<=end && r.to>=start)
                   result.add(r)
            }
            entry = ranges.higherEntry(entry.key)
        }
        return result
    }
    
    List<Range> intersect(int start, int end) {
       def result = getOverlaps(start,end)
       return result.collect { Math.max(it.from, start)..Math.min(it.to, end)}
    }    
    
    List<Range> subtractFrom(int start, int end) {

        List<Range> result = []
        List<Range> cutOut = intersect(start,end)       
        
        // None of the regions in our bed file overlap the range specified,
        // so just return the whole range
        if(cutOut.isEmpty())
            return [start..end-1]
        
        Range lastRange 
        if(start < cutOut[0].from)
            lastRange = start-1..start-1
        else {
            lastRange = cutOut[0]
        }
            
        for(Range r in cutOut) {
            if(lastRange.is(r))
                continue
            if(lastRange.to < r.from) {
              result.add((lastRange.to+1)..r.from-1)
            }
            lastRange = r
        }
        if(lastRange.to<end)
          result.add((lastRange.to+1)..(end-1))
        return result
    }    
    
    Range nextRange(int pos) {
        def entry = [key: pos]
        while(true) {
          entry = ranges.higherEntry(entry.key)
          if(!entry)
              return null
          List<IntRange> nextRanges = entry.value.grep { it.from >= pos } 
          if(nextRanges)
              return nextRanges[0]
        }
    }
    
    Range previousRange(int pos) {
        def entry = [key: pos]
        while(true) {
          entry = ranges.lowerEntry(entry.key)
          if(!entry)
              return null
          List<IntRange> nextRanges = entry.value.grep { it.to <= pos } 
          if(nextRanges)
              return nextRanges[0]
        }
    }
    
    Range nearest(int pos) {
        List<IntRange> overlaps = this.getOverlaps(pos)
        if(overlaps) {
            return overlaps.min {Math.min(it.to-pos, pos-it.from)}
        }
        else {
            IntRange prv = previousRange(pos)
            IntRange nxt = nextRange(pos)
            if(!prv)
                return nxt
            if(!nxt)
                return prv
            
            return (pos-prv.to) < (nxt.from - pos) ? prv : nxt
        }
    }
    
    void dump() {
        // Get the starting point of all the regions
        def regions = ranges.keySet() as List
        def lastRange = ranges.lastEntry().value[0]
        if(lastRange)
          regions += lastRange.to
        
        def sizes = []
        for(int i=0; i<regions.size(); ++i) {
            if(i>0) {
                int regionSize = (regions[i] - regions[i-1])
                sizes << regionSize
            }
        }
        
        // Maximum region size
        int maxSize = regions.max()
        
        // Scale down if necessary
        int width = sizes?sizes.sum():0
        def printSizes = sizes
        println "Total width = $width"
        println "Sizes = $sizes"
        if(width > 80) {
            printSizes = sizes*.multiply( 80.0f/width).collect { Math.round(it).toInteger() }
        }
        
        // now print out a representation of the regions
        println("|"+[sizes,printSizes].transpose().collect { it[0].toString().center(it[1],"-") }.join("|") +"|")
//        println("|"+sizes.collect { "-" * it }.join("|")+"|")
    }

    @Override
    public Iterator<IntRange> iterator() {
        return new Iterator<IntRange>() {
            Integer pos = -1
            int index = 0
            List<IntRange> activeRanges = []
            IntRange nextRange = null
            
            boolean hasNext() {
                if(nextRange)
                    return true
                    
                // Note: pos becomes null when iteration through index exhausted
                // nextRange is set null at each call of next()
                while(pos != null && (nextRange==null || nextRange.from < pos)) {
                  nextRange = findNext()
                }
//                activeRanges.add(nextRange)
                
                return nextRange != null
            }
            
            
            IntRange next() {
                
                if(!hasNext()) // Note: populates nextRange
                    throw new IndexOutOfBoundsException("No more ranges in this index")
                
                IntRange result = nextRange
                nextRange = null
                return result
            }
            
            IntRange findNext() {
                if(pos == -1) {
                    pos = ranges.firstKey()
                }
                else
                if(index > ranges[pos].size()-1) {
                    index = 0
                    
                    pos = ranges.higherKey(pos)
                    while(pos != null && ranges[pos].isEmpty()) {
                      pos = ranges.higherKey(pos)
                    }
                }
                
                if(pos == null) 
                    return null
                    
//                activeRanges.removeAll { !ranges[pos].contains(it) }
                
                return ranges[pos][index++]
            }
            
            void remove() {
                throw new UnsupportedOperationException()
            }
        }
    }
    
}