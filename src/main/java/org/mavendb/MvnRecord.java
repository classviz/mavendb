package org.mavendb;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigInteger;
import org.eclipse.persistence.annotations.Cache;
import org.eclipse.persistence.annotations.CacheType;

/**
 * JPA Persistent class for table <code>artifactinfo</code>.
 */
@Entity
@Table(name = MvnRecord.TABLE_NAME)
@Cache(type = CacheType.NONE)  // Does not preserve object identity and does not cache objects.
@NamedQueries({
    @NamedQuery(name = "MvnRecord.findBySeqid", query = "SELECT a FROM MvnRecord a WHERE a.seqid = :seqid"),
    @NamedQuery(name = "MvnRecord.findByMajorVersion", query = "SELECT a FROM MvnRecord a WHERE a.majorVersion = :majorVersion")
})
public class MvnRecord implements Serializable {

    private static final long serialVersionUID = 1L;
    public static final String TABLE_NAME = "record";

    @Id
    @Basic(optional = false)
    @Column(name = "seqid")
    private Long seqid;

    @Column(name = "major_version")
    private Integer majorVersion;
    @Column(name = "version_seq")
    private BigInteger versionSeq;

    /**
     * We treat MySQL JSON data type as String.
     */
    @Column(name = "json")
    private String json;

    public MvnRecord() {
    }

    public MvnRecord(Long seqid) {
        this.seqid = seqid;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        hash += (this.seqid != null ? this.seqid.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof MvnRecord)) {
            return false;
        }
        MvnRecord other = (MvnRecord) object;
        return (other.seqid != null) && other.seqid.equals(this.seqid);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[ seqid=" + this.seqid + " ]";
    }

    public Long getSeqid() {
        return seqid;
    }

    public void setSeqid(Long seqid) {
        this.seqid = seqid;
    }

    public Integer getMajorVersion() {
        return majorVersion;
    }

    public void setMajorVersion(Integer majorVersion) {
        this.majorVersion = majorVersion;
    }

    public BigInteger getVersionSeq() {
        return versionSeq;
    }

    public void setVersionSeq(BigInteger versionSeq) {
        this.versionSeq = versionSeq;
    }

    public String getJson() {
        return json;
    }

    public void setJson(String json) {
        this.json = json;
    }

}
