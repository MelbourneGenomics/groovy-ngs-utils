import au.com.bytecode.opencsv.CSVWriter
import groovy.xml.StreamingMarkupBuilder

int count = 1

class VCFSummaryStats {
    
    int total = 0
    
    int excludeByDiff = 0
    
    int excludeByCons = 0
    
    int excludeByMaf = 0
    
    int excludeByTarget = 0
    
    int excludeComplex = 0
    
    int excludeNotPresent = 0
    
    int excludeByPreFilter = 0
    
    int excludeByFilter = 0
    
    int excludeByMasked = 0
    
    int totalIncluded
    
}

banner = """

"""

Cli cli = new Cli(usage:"VCFtoHTML <options>")
cli.with {
    i 'vcf File', args: Cli.UNLIMITED, required:true
    p 'PED file describing relationships between the samples in the data', args:1
    o 'Output HTML file', args:1, required:true
    f 'Comma separated list of families to export', args:1
    a 'comma separated aliases for samples in input VCF at corresponding -i position', args: Cli.UNLIMITED
    target 'Exclude variants outside this region', args:1
    chr 'Confine analysis to chromosomel', args:Cli.UNLIMITED
    diff 'Only output variants that are different between the samples'
    maxMaf 'Filter out variants above this MAF', args:1
    maxVep 'Only write a single line for each variant, containing the most severe consequence'
    tsv 'Output TSV format with the same variants', args:1
    filter 'A groovy expression to be used as a filter on variants before inclusion', args: Cli.UNLIMITED
    prefilter 'A groovy expression to be used as a pre-filter before any other steps', args: Cli.UNLIMITED
    nocomplex 'Ignore complex variants (regions where multiple variants overlap) in diff mode'
    nomasked 'Ignore variants in regions of the genome that are masked due to repeats'
    stats 'Write statistics to file', args:1
}

opts = cli.parse(args)
if(!opts)
    System.exit(1)

def allSamples = opts.is.collect { new VCF(it).samples }.flatten()

Pedigrees pedigrees = null
if(opts.p) {
    pedigrees = Pedigrees.parse(opts.p)
}
else {
    // Find all the samples
    println "Found samples: " + allSamples
    pedigrees = Pedigrees.fromSingletons(allSamples) 
}

BED target = null
if(opts.target) {
    target = new BED(opts.target).load()
}

def exportSamples = pedigrees.families.values().collect { it.individuals*.id }.flatten().grep { it in allSamples };

def exportFamilies = pedigrees.families.keySet()
if(opts.f) {
    exportFamilies = opts.f.split(",").collect { it.trim() }
    exportSamples = exportSamples.grep { s -> exportFamilies.any { f-> pedigrees.families[f].samples.contains(s)  }}
    pedigrees.families.keySet().grep { !exportFamilies.contains(it) }.each { pedigrees.removeFamily(it) }
}
println "Samples to export: $exportSamples" 

if(exportSamples.empty) {
    System.err.println "ERROR: No samples from families $exportFamilies found in VCF file $opts.i"
    System.err.println "\nSamples found in VCF are: " + (new VCF(opts.i)).samples
}


float MAF_THRESHOLD=0.05
if(opts.maxMaf) 
    MAF_THRESHOLD=opts.maxMaf.toFloat()

List<String> filters = []
if(opts.filters) {
    filters = opts.filters 
}        

List<String> preFilters = []
if(opts.prefilters) {
    preFilters = opts.prefilters 
}

VCFSummaryStats stats = new VCFSummaryStats()

List<VCF> vcfs 
   vcfs = opts.is.collect { VCF.parse(it) {  v ->
      if(opts.chrs && !(v.chr in opts.chrs ))
          return false
              
        if(!preFilters.every { Eval.x(v, it) }) {
            ++stats.excludeByPreFilter
            return false
        }
   } 
}
   
List<Regions> vcfRegions 
if(opts.nocomplex)
    vcfRegions = vcfs*.toRegions()   

// -------- Handle duplicate sample ids ----------------
// If VCFs have samples with the same id, we now need to rename them to get a sensible result
// We also have to rename any instance of those in the "exportSamples" since they must match
// for export to work
List accumulatedSamples = vcfs[0].samples.clone()
int n = 0
if(vcfs.size() > 1) {
    for(VCF vcf in vcfs[1..-1]) {
        vcf.samples.collect { s ->
            String newSample = s
            int i = 1
            while(newSample in accumulatedSamples) {
                newSample = s + "_$i"    
                ++i
            }
            if((newSample != s) && (s in exportSamples)) {
                println "Rename sample $s to $newSample in vcf $n"
                exportSamples << newSample
                if(pedigrees.subjects[s]) {
                    pedigrees.subjects[s].copySubject(s, newSample)
                    pedigrees.subjects[newSample] = pedigrees.subjects[s]
                }
                    
                vcf.renameSample(s, newSample)
            }
            accumulatedSamples << newSample
            return newSample
        }
        ++n
    }
}

println "Pedigree subjects are " + pedigrees.subjects.keySet()

// -------- Handle Aliasing of Samples ----------------
aliases = null
if(opts.a) {
    aliases = opts['as'].collect { it.split "," }
    
    Map<String,String> sampleMap = [ vcfs*.samples.flatten(), aliases.flatten() ].transpose().collectEntries()
    
    println "Sample map = " + sampleMap
    
    vcfs.each { VCF vcf ->
        for(String s in vcf.samples) {
            if(s in pedigrees.subjects.keySet()) { // may have been removed by filter on export samples
                println "Pedigree rename $s => " + sampleMap[s]
                pedigrees.renameSubject(s, sampleMap[s])
            }            
            vcf.renameSample(s, sampleMap[s])
        }
    }
    
    exportSamples = exportSamples.collect { id -> sampleMap[id] }
}

println "Samples in vcfs are: " + vcfs.collect { vcf -> vcf.samples.join(",") }.join(" ")
println "Export samples are: " + exportSamples

boolean hasVEP = true
def noVeps = vcfs.findIndexValues { !it.hasInfo("CSQ") }
if(noVeps) {
    System.err.println "INFO: This program requires that VCFs have VEP annotations for complete output. Output results will not have annotations and filtering may be ineffective."
    System.err.println "\n" + noVeps.collect { opts.is[(int)it]}.join("\n") + "\n"
    hasVEP = false
    // System.exit(1)
}

boolean hasSNPEFF = vcfs.any { it.hasInfo("EFF") }

def js = [
    "http://ajax.aspnetcdn.com/ajax/jQuery/jquery-2.1.0.min.js",
    "http://cdn.datatables.net/1.10.0/js/jquery.dataTables.min.js",
    "http://ajax.googleapis.com/ajax/libs/jqueryui/1.10.4/jquery-ui.min.js",
    "http://cdnjs.cloudflare.com/ajax/libs/jquery-layout/1.3.0-rc-30.79/jquery.layout.min.js",
    "http://igv.org/web/beta/igv-beta.js"
]

def css = [
    "http://cdn.datatables.net/1.10.0/css/jquery.dataTables.css",
    "http://ajax.googleapis.com/ajax/libs/jqueryui/1.10.4/themes/smoothness/jquery-ui.css",
    "http://cdnjs.cloudflare.com/ajax/libs/jquery-layout/1.3.0-rc-30.79/layout-default.css",
    "http://fonts.googleapis.com/css?family=PT+Sans:400,700",
    "http://fonts.googleapis.com/css?family=Open+Sans'",
    "http://maxcdn.bootstrapcdn.com/font-awesome/4.2.0/css/font-awesome.min.css",
    "http://igv.org/web/beta/igv-beta.css"
]

def EXCLUDE_VEP = ["synonymous_variant","intron_variant","intergenic_variant","upstream_gene_variant","downstream_gene_variant","5_prime_UTR_variant"]

def VEP_PRIORITY = [
        "transcript_ablation",
        "splice_donor_variant",
        "splice_acceptor_variant",
        "stop_gained",
        "frameshift_variant",
        "stop_lost",
        "initiator_codon_variant",
        "inframe_insertion",
        "inframe_deletion",
        "missense_variant",
        "transcript_amplification",
        "splice_region_variant",
        "incomplete_terminal_codon_variant",
        "synonymous_variant",
        "stop_retained_variant",
        "coding_sequence_variant",
        "mature_miRNA_variant",
        "5_prime_UTR_variant",
        "3_prime_UTR_variant",
        "non_coding_exon_variant",
        "nc_transcript_variant",
        "intron_variant",
        "NMD_transcript_variant",
        "upstream_gene_variant",
        "downstream_gene_variant",
        "TFBS_ablation",
        "TFBS_amplification",
        "TF_binding_site_variant",
        "regulatory_region_variant",
        "regulatory_region_ablation",
        "regulatory_region_amplification",
        "feature_elongation",
        "feature_truncation",
        "intergenic_variant"
]

def json(obj) {
    groovy.json.JsonOutput.toJson(obj)
}

findMaxMaf = { vep -> 
    [vep.EA_MAF, vep.ASN_MAF, vep.EUR_MAF].collect{it?it.split('&'):[]}.flatten().collect { it.toFloat()}.max() ?: 0.0f
}

def baseColumns = new LinkedHashMap()
baseColumns += [
    'tags' : {''}, // reserved for tags
    'chr' : {it.chr },
    'pos' : {it.pos },
    'ref': {it.ref },
    'alt': {it.alt },
    'qual': {it.qual },
    'depth': {it.info.DP },
    'families' : { v -> 
        def fcount = v.pedigrees.count {  ped ->
            def result = ped.samples.any { 
                v.sampleDosage(it) 
            } 
            return result
        } 
        return fcount;
     }
]

def consColumns = [ 
    'gene' : {it['SYMBOL']},
    'cons' : {vep -> vep['Consequence'].split('&').min { VEP_PRIORITY.indexOf(it) } },
    'maf'  : findMaxMaf
]


def tsvWriter = null
if(opts.tsv)
    tsvWriter = new CSVWriter(new File(opts.tsv).newWriter(), '\t' as char)

    
LOWER_CASE_BASE_PATTERN = ~/[agct]/
    
new File(opts.o).withWriter { w ->
   
    // Merge all the VCFs together
    VCF merged = vcfs[0]
    
    if(vcfs.size()>1) {
        vcfs[1..-1].eachWithIndex { vcf, vcfIndex ->
            Utils.time("Merge VCF $vcfIndex") {
                 merged = merged.merge(vcf) 
            }
        }
    }
        
    println "Merged samples are " + merged.samples
    
    if(opts.tsv) 
        tsvWriter.writeNext((baseColumns*.key+ consColumns*.key +exportSamples) as String[])
            
    w.println """
    <html>
        <head>
    """
    w.println css.collect{"<link rel='stylesheet' href='$it'/>"}.join("\n").stripIndent()
    w.println js.collect{"<script type='text/javascript' src='$it'></script>"}.join("\n").stripIndent()
    
    // Embed the main vcf.js
    def vcfjs 
    def fileVcfJsPath = "src/main/resources/vcf.js"
    if(new File(fileVcfJsPath).exists())
        vcfjs = new File(fileVcfJsPath).text
    else
        vcfjs = this.class.classLoader.getResourceAsStream("vcf.js").text
    
    w.println "<script type='text/javascript'>\n$vcfjs\n</script>"
    
    w.println "<script type='text/javascript'>"
   
    w.println "var variants = ["
    int i=0;
    int lastLines = 0
    Variant last = null;
    merged.each { v->
        
        ++stats.total
        
        if((target != null) && !(v in target)) {
            ++stats.excludeByTarget
            return
        }
        
        List baseInfo = baseColumns.collect { baseColumns[it.key](v) }
        List dosages = exportSamples.collect { v.sampleDosage(it) }
        if(dosages.every { it==0}) {
            ++stats.excludeNotPresent
            if(!opts.f) {
                println "WARNING: variant at $v.chr:$v.pos $v.ref/$v.alt in VCF but not genotyped as present for any sample"
            }
            return
        }
            
        if(opts.diff && dosages.clone().unique().size()==1)  {
            ++stats.excludeByDiff
            return
        }
            
        def refCount = v.getAlleleDepths(0)
        def altCount = v.getAlleleDepths(1)
        
//        println v.toString() + v.line.split("\t")[8..-1] + " ==> " + "$refCount/$altCount"
        
        
        if(i++>0 && lastLines > 0)
            w.println ","
        
        List<Object> consequences = [
            [
                Consequence: 'Unknown'
            ]
        ]
        
        if(hasVEP) {
            try {
                if(opts.maxVep) { 
                    consequences = [v.maxVep]
                }
                else {
                    consequences = v.vepInfo
                }
            }
            catch(Exception e) {
                // Ignore
            }
        }
        else
        if(hasSNPEFF) {
            consequences = v.snpEffInfo
        }
        
        lastLines = 0
        boolean excludedByCons = true
        boolean printed = false
        consequences.grep { !it.Consequence.split('&').every { EXCLUDE_VEP.contains(it) } }.each { vep ->
            
            if(EXCLUDE_VEP.contains(consColumns['cons'](vep))) {
                return
            }
            excludedByCons = false
            
            if(hasVEP) {
                if(findMaxMaf(vep)>MAF_THRESHOLD)  {
                    ++stats.excludeByMaf
                    return
                }
            }
            
            if(opts.nocomplex) {
                if(vcfRegions.any { Regions regions -> regions.getOverlaps(v.chr, v.pos-1,v.pos+1).size() > 1}) {
                    ++stats.excludeComplex
                    return
                }
            }
            
            if(opts.nomasked) {
                if(v.alt.find(LOWER_CASE_BASE_PATTERN) || v.ref.find(LOWER_CASE_BASE_PATTERN)) {
                    ++stats.excludeByMasked
                    return 
                }
            } 
            
            if(!filters.every { Eval.x(v, it) }) {
                ++stats.excludeByFilter
                return
            }
            
            if(lastLines>0)
                w.println ","
                
            List vepInfo = consColumns.collect { name, func -> func(vep) }
            w.print(groovy.json.JsonOutput.toJson(baseInfo+vepInfo+dosages + [ [refCount,altCount].transpose() ]))
//            println(groovy.json.JsonOutput.toJson(baseInfo+vepInfo+dosages + [ [refCount,altCount].transpose() ]))
            
            printed = true
            
            if(opts.tsv)
                tsvWriter.writeNext((baseInfo+vepInfo+dosages) as String[])

            ++lastLines
        }
        
        if(printed) {
            ++stats.totalIncluded
        }
        
        last = v;
        if(excludedByCons)
            ++stats.excludeByCons
        
        
    }
    w.println """];"""
    
    w.println "var columnNames = ${json(baseColumns*.key + consColumns*.key + exportSamples)};"
    
    w.println """
    var samples = ${json(exportSamples)};

    var pedigrees = ${pedigrees.toJson()};

    var variantTable = null;
    \$(document).ready(function() {
        variantTable = \$.VariantTable('variantTable', samples, variants);
    });
    </script>
    <style type='text/css'>
    td.vcfcol { text-align: center; }
    tr.highlight, tr.highlighted {
        background-color: #ffeeee !important;
    }

    #tags { float: right; }
            
    .assignedTag, .rowTagDiv {
        background-color: red;
        border-radius: 5px;
        color: white;
        display: inline;
        padding: 3px 5px;
        font-size: 75%;
        margin: 3px;
    }
    .rowTagDiv { font-size: 55%; }
    .tag0 { background-color: #33aa33; }
    .tag1 { background-color: #3333ff; }
    .tag2 { background-color: #aa33aa; }
    .tag3 { background-color: #ff6600; }
    .unassignedTag { display: none; }

    </style>
    """;
    
   
    w.println """
    </head>
    <body>
    <p>Loading ...</p>
        <div class="ui-layout-north">
            <h1>VCF File ${new File(opts.i).name}</h1>
            <span id=filterOuter><span id=filters> </span></span>
            <div id=filterHelp style='display:none'> </div>
        </div>
        <div class="ui-layout-center">
            <div id=tableHolder>
            </div>
        </div>
        <div class="ui-layout-south">Report created ${new Date()}</div>
        <div class="ui-layout-east" id="ui-layout-east"></div>
    </body>
    </html>
    """
}

if(tsvWriter != null)
    tsvWriter.close()

println " Summary ".center(80,"=")

Writer statsWriter = null
if(opts.stats)
    statsWriter = new File(opts.stats).newWriter()
    
try {    
    ["total", "excludeByPreFilter","excludeByDiff", "excludeByCons", "excludeByMaf", "excludeComplex", "excludeByMasked", "excludeByFilter", "excludeByTarget", "excludeNotPresent","totalIncluded"].each { prop ->
        println prop.padLeft(20) + " :" + stats[prop]
        if(opts.stats) {
            statsWriter.println([prop,stats[prop]].join('\t'))
        }
    }
}
finally {
    statsWriter.close()
}
    
    
    
    
