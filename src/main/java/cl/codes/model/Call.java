package cl.codes.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "calls")
public class Call {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "original_audio", length = 300)
    private String originalAudio;

    @Lob
    private String transcription;

    @Lob
    @Column(name = "operational_summary")
    private String operationalSummary;

    @Column(length = 20)
    private String priority;

    private int urgentScore;
    private int redScore;
    private int mediumScore;
    private int greenScore;

    @Lob
    @Column(name = "highlighted_words")
    private String highlightedWords;

    @Column(name = "detected_address", length = 200)
    private String detectedAddress;

    private Double latitude;
    private Double longitude;

    private boolean assigned = false;

    @Column(name = "assigned_operator", length = 50)
    private String assignedOperator;

    @Column(name = "assignment_date")
    private LocalDateTime assignmentDate;

    @Column(name = "closure_date")
    private LocalDateTime closureDate;

    @Lob
    @Column(name = "closure_comment")
    private String closureComment;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "created_by_user", length = 50)
    private String createdByUser;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getOriginalAudio() { return originalAudio; }
    public void setOriginalAudio(String originalAudio) { this.originalAudio = originalAudio; }

    public String getTranscription() { return transcription; }
    public void setTranscription(String transcription) { this.transcription = transcription; }

    public String getOperationalSummary() { return operationalSummary; }
    public void setOperationalSummary(String operationalSummary) { this.operationalSummary = operationalSummary; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public int getUrgentScore() { return urgentScore; }
    public void setUrgentScore(int urgentScore) { this.urgentScore = urgentScore; }

    public int getRedScore() { return redScore; }
    public void setRedScore(int redScore) { this.redScore = redScore; }

    public int getMediumScore() { return mediumScore; }
    public void setMediumScore(int mediumScore) { this.mediumScore = mediumScore; }

    public int getGreenScore() { return greenScore; }
    public void setGreenScore(int greenScore) { this.greenScore = greenScore; }

    public String getHighlightedWords() { return highlightedWords; }
    public void setHighlightedWords(String highlightedWords) { this.highlightedWords = highlightedWords; }

    public String getDetectedAddress() { return detectedAddress; }
    public void setDetectedAddress(String detectedAddress) { this.detectedAddress = detectedAddress; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public boolean isAssigned() { return assigned; }
    public void setAssigned(boolean assigned) { this.assigned = assigned; }

    public String getAssignedOperator() { return assignedOperator; }
    public void setAssignedOperator(String assignedOperator) { this.assignedOperator = assignedOperator; }

    public LocalDateTime getAssignmentDate() { return assignmentDate; }
    public void setAssignmentDate(LocalDateTime assignmentDate) { this.assignmentDate = assignmentDate; }

    public LocalDateTime getClosureDate() { return closureDate; }
    public void setClosureDate(LocalDateTime closureDate) { this.closureDate = closureDate; }

    public String getClosureComment() { return closureComment; }
    public void setClosureComment(String closureComment) { this.closureComment = closureComment; }

    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }

    public String getCreatedByUser() { return createdByUser; }
    public void setCreatedByUser(String createdByUser) { this.createdByUser = createdByUser; }
}


