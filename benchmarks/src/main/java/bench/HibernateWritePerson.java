package bench;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "BenchWritePerson")
@Table(name = "bench_write_people")
public class HibernateWritePerson {
    @Id public int id;
    @Column(nullable = false) public String email;
    @Column(nullable = false) public String name;
    @Column(nullable = false) public int score;
}
