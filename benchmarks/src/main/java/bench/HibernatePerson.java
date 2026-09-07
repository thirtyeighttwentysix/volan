package bench;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "BenchPerson")
@Table(name = "bench_people")
public class HibernatePerson {
    @Id public int id;
    @Column(nullable = false) public String email;
    @Column(nullable = false) public String name;
    @Column(nullable = false) public int score;
}
